//! Native Android host for the FM-1 emulator.
//!
//! The emulated chip runs on a dedicated worker thread. An AAudio callback only drains a
//! lock-free SPSC audio ring; it never executes guest instructions, allocates, or waits for the
//! emulator. The Android UI calls this crate through a small C ABI implemented in `ffi.rs`.


use fm1_core::Cpu;
use fm1_soc::{machine, stock_boot, stock_from_package, Action, Board, Soc, FLASH_SIZE};
use rtrb::{Consumer, Producer, RingBuffer};
use std::ffi::c_char;
use std::ptr;
use std::sync::atomic::{AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

#[path = "../../fm1-live/src/panel.rs"]
mod panel;
#[path = "../../fm1-live/src/panel_smk.rs"]
mod panel_smk;

const RAM_SIZE: usize = 1 << 20;
const RAM_BASE: u32 = 0x01c0_0000;
const AUDIO_RING_FRAMES: usize = 16_384;
const TARGET_MIN_FRAMES: usize = 512;      // 11.6 ms
const TARGET_INITIAL_FRAMES: usize = 1_024; // 23.2 ms
const TARGET_MAX_FRAMES: usize = 4_096;    // 92.9 ms
const TARGET_STEP_FRAMES: usize = 256;     // 5.8 ms

#[derive(Clone)]
struct PanelSnapshot {
    board: Board,
    lcd: Vec<u16>,
    leds: [f32; 41],
    down: [bool; 64],
    enc_clicks: [i32; 8],
    turning: [u32; 8],
    pot: u16,
    pads: [u32; 16],
    analog: [u16; 23],
    revision: u64,
}

impl PanelSnapshot {
    fn new(board: Board) -> Self {
        let mut analog = [0u16; 23];
        analog[fm1_soc::smk::ANALOG_PITCH as usize] = fm1_soc::smk::PITCH_REST;
        analog[fm1_soc::smk::ANALOG_MOD as usize] = fm1_soc::smk::MOD_REST;
        Self {
            board,
            lcd: vec![0; 240 * 240],
            leds: [0.0; 41],
            down: [false; 64],
            enc_clicks: [0; 8],
            turning: [0; 8],
            pot: 900,
            pads: [0; 16],
            analog,
            revision: 0,
        }
    }
}

#[derive(Default, Clone, Copy)]
struct RuntimeStatus {
    emulated_ms: u64,
    speed: f32,
    required_load_percent: f32,
    underruns: u64,
    firmware_starved_halves: u64,
    jit_state: u32, // 0 warming up, 1 native JIT active, 2 executable memory denied
    halted: bool,
    clock_mhz: u32,
}

struct PanelRenderer {
    board: Option<Board>,
    theme: usize,
    base: Vec<u32>,
    buffer: Vec<u32>,
}

impl PanelRenderer {
    fn new() -> Self {
        Self { board: None, theme: usize::MAX, base: Vec::new(), buffer: Vec::new() }
    }

    fn draw(&mut self, snapshot: &PanelSnapshot, theme_id: usize, destination: &mut [u32]) -> Result<(usize, usize), &'static str> {
        let theme_id = theme_id.min(panel::THEMES.len().saturating_sub(1));
        if self.board != Some(snapshot.board) || self.theme != theme_id {
            self.board = Some(snapshot.board);
            self.theme = theme_id;
            self.base = if snapshot.board == Board::Smk37 {
                panel_smk::base()
            } else {
                panel::base(&panel::THEMES[theme_id].2)
            };
            self.buffer.resize(self.base.len(), 0);
        }
        match snapshot.board {
            Board::Fm1 => {
                let down: [bool; 41] = std::array::from_fn(|i| snapshot.down[i]);
                let clicks: [i32; 7] = std::array::from_fn(|i| snapshot.enc_clicks[i]);
                let turning: [u32; 8] = snapshot.turning;
                let state = panel::State {
                    lcd: &snapshot.lcd,
                    enc_clicks: &clicks,
                    pot: snapshot.pot,
                    down: &down,
                    leds: &snapshot.leds,
                    turning: &turning,
                };
                panel::draw(&mut self.buffer, &self.base, &state, &panel::THEMES[theme_id].2);
                for pixel in self.buffer.iter_mut() {
                    *pixel |= 0xff00_0000;
                }
                let (w, h) = (panel::W, panel::H);
                if destination.len() < self.buffer.len() { return Err("panel buffer too small"); }
                destination[..self.buffer.len()].copy_from_slice(&self.buffer);
                Ok((w, h))
            }
            Board::Smk37 => {
                let clicks: [i32; 8] = snapshot.enc_clicks;
                let turning: [u32; 8] = snapshot.turning;
                let down = snapshot.down;
                let leds: [f32; 19] = std::array::from_fn(|i| snapshot.leds[i]);
                let pad_force: [u16; 16] = std::array::from_fn(|i| snapshot.analog[i]);
                let faders: [u16; 4] = std::array::from_fn(|i| snapshot.analog[16 + i]);
                let state = panel_smk::State {
                    lcd: &snapshot.lcd,
                    enc_clicks: &clicks,
                    turning: &turning,
                    down: &down,
                    leds: &leds,
                    pads: &snapshot.pads,
                    pad_force: &pad_force,
                    faders: &faders,
                    pitch: snapshot.analog[fm1_soc::smk::ANALOG_PITCH as usize],
                    modw: snapshot.analog[fm1_soc::smk::ANALOG_MOD as usize],
                };
                panel_smk::draw(&mut self.buffer, &self.base, &state);
                for pixel in self.buffer.iter_mut() {
                    *pixel |= 0xff00_0000;
                }
                let (w, h) = (panel_smk::W, panel_smk::H);
                if destination.len() < self.buffer.len() { return Err("panel buffer too small"); }
                destination[..self.buffer.len()].copy_from_slice(&self.buffer);
                Ok((w, h))
            }
        }
    }
}

struct Machine {
    cpu: Cpu,
    cpu1: Option<Cpu>,
    soc: Soc,
    pending_tick: bool,
    halted: bool,
}

impl Machine {
    fn boot(package: &[u8], saved_flash: Option<&[u8]>, mhz: u32) -> Result<Self, String> {
        let stock = stock_from_package(package, None)?;
        let mut soc = Soc::new(RAM_SIZE, stock.xip);
        soc.periph.hz = mhz as u64 * 1_000_000;
        soc.periph.skip_idle = true;
        // The FM-1 host runner normally completes LCD transfers immediately; this avoids
        // adding emulated display-wire time to the CPU budget on a phone.
        soc.lcd_timed = false;
        let mut cpu = Cpu::new(stock.entry);
        cpu.jit_on = true;
        cpu.user = true;
        let params = RAM_BASE + 0x7fc00;
        soc.write32(params, params + 0x40);
        cpu.regs[0] = stock_boot(&mut soc, &stock.flash, params, stock.board);
        soc.sfc_boot(stock.key, stock.app_end);
        if let Some(flash) = saved_flash.filter(|bytes| bytes.len() == FLASH_SIZE) {
            soc.restore_flash(flash);
        }
        Ok(Self { cpu, cpu1: None, soc, pending_tick: false, halted: false })
    }

    fn run(&mut self, instructions: u32) {
        let jit = self.cpu.jit_on;
        let Machine { cpu, cpu1, soc, pending_tick, .. } = self;
        if machine::run_dual(cpu1, soc, instructions, RAM_SIZE, jit,
            |soc, budget| machine::run_blocks(cpu, soc, budget, pending_tick)).is_err() {
            self.halted = true;
        }
    }
}

enum Command {
    Key { id: u8, down: bool, velocity: u32 },
    Encoder { index: u8, clicks: i32 },
    Analog { index: u8, value: u16 },
    Pot(u16),
    ExportFlash(mpsc::Sender<Vec<u8>>),
    Stop,
}

pub struct Engine {
    commands: mpsc::Sender<Command>,
    audio_consumer: Mutex<Consumer<[f32; 2]>>,
    worker: Option<JoinHandle<()>>,
    panel: Arc<Mutex<PanelSnapshot>>,
    renderer: Mutex<PanelRenderer>,
    status: Arc<Mutex<RuntimeStatus>>,
    underruns: Arc<AtomicU64>,
    theme: AtomicUsize,
    hit_y_bits: AtomicU32,
}

impl Engine {
    fn new(package: &[u8], saved_flash: Option<&[u8]>, mhz: u32) -> Result<Self, String> {
        if !(60..=240).contains(&mhz) {
            return Err("Clock must be between 60 and 240 MHz".into());
        }
        let machine = Machine::boot(package, saved_flash, mhz)?;
        let board = machine.soc.board;
        let panel = Arc::new(Mutex::new(PanelSnapshot::new(board)));
        let status = Arc::new(Mutex::new(RuntimeStatus { clock_mhz: mhz, ..RuntimeStatus::default() }));
        let underruns = Arc::new(AtomicU64::new(0));
        let (producer, consumer) = RingBuffer::<[f32; 2]>::new(AUDIO_RING_FRAMES);
        let (commands, receiver) = mpsc::channel();

        let worker_panel = panel.clone();
        let worker_status = status.clone();
        let worker_underruns = underruns.clone();
        let worker = thread::Builder::new().name("fm1-emulation".into())
            .stack_size(2 * 1024 * 1024)
            .spawn(move || run_worker(machine, producer, receiver, worker_panel, worker_status,
                                      worker_underruns))
            .map_err(|e| format!("Could not start emulator thread: {e}"))?;

        Ok(Self {
            commands,
            audio_consumer: Mutex::new(consumer),
            worker: Some(worker),
            panel,
            renderer: Mutex::new(PanelRenderer::new()),
            status,
            underruns,
            theme: AtomicUsize::new(0),
            hit_y_bits: AtomicU32::new(0),
        })
    }

    fn render_audio(&self, output: &mut [f32], frames: usize) -> usize {
        let needed = frames.saturating_mul(2);
        if output.len() < needed { return 0; }
        let mut delivered = 0usize;
        if let Ok(mut consumer) = self.audio_consumer.try_lock() {
            for frame in 0..frames {
                if let Ok([left, right]) = consumer.pop() {
                    output[frame * 2] = left;
                    output[frame * 2 + 1] = right;
                    delivered += 1;
                } else {
                    output[frame * 2] = 0.0;
                    output[frame * 2 + 1] = 0.0;
                }
            }
        } else {
            output[..needed].fill(0.0);
        }
        if delivered < frames {
            self.underruns.fetch_add(1, Ordering::Relaxed);
        }
        delivered
    }

    fn render_panel(&self, destination: &mut [u32]) -> Result<(usize, usize), &'static str> {
        let snapshot = self.panel.lock().map_err(|_| "panel lock poisoned")?.clone();
        let theme = self.theme.load(Ordering::Relaxed);
        self.renderer.lock().map_err(|_| "renderer lock poisoned")?.draw(&snapshot, theme, destination)
    }

    fn panel_size(&self) -> (usize, usize) {
        if self.panel.lock().map(|p| p.board == Board::Smk37).unwrap_or(false) {
            (panel_smk::W, panel_smk::H)
        } else {
            (panel::W, panel::H)
        }
    }

    fn panel_hit(&self, x: f32, y: f32) -> i32 {
        let board = self.panel.lock().map(|p| p.board).unwrap_or(Board::Fm1);
        if board == Board::Smk37 {
            match panel_smk::hit(x, y) {
                Some(panel_smk::Ctl::Enc(i)) => i as i32,
                Some(panel_smk::Ctl::Btn(id)) => 100 + id as i32,
                Some(panel_smk::Ctl::Key(id, fraction)) => {
                    self.hit_y_bits.store(fraction.to_bits(), Ordering::Relaxed);
                    100 + id as i32
                }
                Some(panel_smk::Ctl::Pad(p)) => 200 + p as i32,
                Some(panel_smk::Ctl::Fader(f)) => 300 + f as i32,
                Some(panel_smk::Ctl::Pitch) => 400,
                Some(panel_smk::Ctl::Mod) => 401,
                None => -1,
            }
        } else {
            match panel::hit(x, y) {
                        Some(panel::Ctl::Enc(i)) => i as i32,
                Some(panel::Ctl::Pot) => 7,
                Some(panel::Ctl::Btn(id)) => 100 + id as i32,
                Some(panel::Ctl::Key(id)) => {
                    if let Some((_, _, key_y, _, key_h, _)) = panel::keys().into_iter().find(|key| key.0 == id) {
                        self.hit_y_bits.store(((y - key_y as f32) / key_h as f32).clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
                    }
                    100 + id as i32
                }
                None => -1,
            }
        }
    }

    fn panel_slide(&self, index: u32, y: f32) -> u32 {
        if self.panel.lock().map(|p| p.board != Board::Smk37).unwrap_or(true) { return 0; }
        match index {
            0..=3 => panel_smk::fader_value(index as usize, y) as u32,
            4 => panel_smk::wheel_value(true, y) as u32,
            5 => panel_smk::wheel_value(false, y) as u32,
            _ => 0,
        }
    }

    fn export_flash(&self, destination: &mut [u8]) -> usize {
        // Ask the emulation thread for the live flash image; do not save a stale cached copy.
        let (reply, response) = mpsc::channel();
        if self.commands.send(Command::ExportFlash(reply)).is_err() { return 0; }
        let Ok(image) = response.recv_timeout(Duration::from_secs(2)) else { return 0 };
        let count = destination.len().min(image.len());
        destination[..count].copy_from_slice(&image[..count]);
        count
    }

    fn status(&self) -> RuntimeStatus {
        let mut status = self.status.lock().map(|s| *s).unwrap_or_default();
        status.underruns = self.underruns.load(Ordering::Relaxed);
        status
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        let _ = self.commands.send(Command::Stop);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

fn run_worker(
    mut machine: Machine,
    mut producer: Producer<[f32; 2]>,
    commands: mpsc::Receiver<Command>,
    panel_state: Arc<Mutex<PanelSnapshot>>,
    status: Arc<Mutex<RuntimeStatus>>,
    underruns: Arc<AtomicU64>,
) {
    let mut last_panel = Instant::now();
    let mut report_at = Instant::now();
    let mut previous_ms = machine.soc.periph.ms();
    let mut previous_busy = Duration::ZERO;
    let mut total_busy = Duration::ZERO;
    let mut previous_underruns = underruns.load(Ordering::Relaxed);
    let mut calm_since = Instant::now();
    let mut target = TARGET_INITIAL_FRAMES;
    let mut enc_turning = [0u32; 8];
    let mut enc_clicks = [0i32; 8];
    let mut analog = [0u16; 23];
    analog[fm1_soc::smk::ANALOG_PITCH as usize] = fm1_soc::smk::PITCH_REST;
    analog[fm1_soc::smk::ANALOG_MOD as usize] = fm1_soc::smk::MOD_REST;

    loop {
        let mut stop = false;
        while let Ok(command) = commands.try_recv() {
            match command {
                Command::Key { id, down, velocity } => {
                    if machine.soc.board == Board::Smk37 {
                        if down { machine.soc.periph.apply(&Action::Vel { us: velocity.max(2) }); }
                    } else if down && id >= 14 {
                        machine.soc.periph.apply(&Action::KeyVel { vel: velocity.clamp(1, 127) as u8 });
                    }
                    machine.soc.periph.apply(&Action::Key { id, down });
                }
                Command::Encoder { index, clicks } => {
                    let matrix = if machine.soc.board == Board::Smk37 { index as u8 } else {
                        panel::ENCS.get(index as usize).map_or(0, |entry| entry.1)
                    };
                    machine.soc.periph.apply(&Action::Enc { enc: matrix, clicks });
                    if (index as usize) < enc_clicks.len() {
                        enc_clicks[index as usize] = enc_clicks[index as usize].saturating_add(clicks);
                        enc_turning[index as usize] = 10;
                    }
                }
                Command::Analog { index, value } => {
                    let value = value.min(1023);
                    machine.soc.periph.apply(&Action::Adc { ch: index, value });
                    if (index as usize) < analog.len() { analog[index as usize] = value; }
                }
                Command::Pot(value) => {
                    machine.soc.periph.apply(&Action::Adc { ch: 4, value: value.min(1023) });
                }
                Command::ExportFlash(reply) => {
                    let _ = reply.send(machine.soc.flash.mem.clone());
                }
                Command::Stop => { stop = true; break; }
            }
        }
        if stop { break; }

        let fill = producer.buffer().capacity() - producer.slots();
        if fill < target {
            if machine.halted {
                while producer.slots() > 0 && (producer.buffer().capacity() - producer.slots()) < target {
                    if producer.push([0.0, 0.0]).is_err() { break; }
                }
                thread::sleep(Duration::from_millis(2));
            } else {
                let start = Instant::now();
                machine.run(20_000);
                total_busy += start.elapsed();
                let samples = std::mem::take(&mut machine.soc.periph.audio);
                for pair in samples.chunks_exact(2) {
                    if producer.push([pair[0] as f32 / 32768.0, pair[1] as f32 / 32768.0]).is_err() {
                        break;
                    }
                }
            }
        } else {
            thread::sleep(Duration::from_micros(500));
        }

        let now = Instant::now();
        if now.duration_since(last_panel) >= Duration::from_millis(40) {
            last_panel = now;
            if let Ok(mut snapshot) = panel_state.lock() {
                snapshot.board = machine.soc.board;
                snapshot.lcd.copy_from_slice(&machine.soc.lcd.fb);
                snapshot.down = [false; 64];
                if machine.soc.board == Board::Smk37 {
                    snapshot.down[..machine.soc.periph.smk.down.len()].copy_from_slice(&machine.soc.periph.smk.down);
                    snapshot.leds = [0.0; 41];
                    let smk_leds = machine.soc.periph.smk.led_take();
                    snapshot.leds[..smk_leds.len()].copy_from_slice(&smk_leds);
                    snapshot.pads = machine.soc.periph.smk.pads;
                    for i in 0..analog.len() { snapshot.analog[i] = machine.soc.periph.smk.analog(i as u8); }
                } else {
                    snapshot.down[..41].copy_from_slice(&machine.soc.periph.keys_down);
                    snapshot.leds = machine.soc.periph.led_shown();
                    snapshot.pot = machine.soc.periph.adc_value[4];
                }
                snapshot.enc_clicks = enc_clicks;
                snapshot.turning = enc_turning;
                snapshot.revision = snapshot.revision.wrapping_add(1);
            }
            for value in &mut enc_turning { *value = value.saturating_sub(1); }
        }

        if now.duration_since(report_at) >= Duration::from_secs(1) {
            let emulated_ms = machine.soc.periph.ms();
            let emulated_delta = emulated_ms.saturating_sub(previous_ms) as f32 / 1000.0;
            let busy_delta = total_busy.saturating_sub(previous_busy).as_secs_f32();
            let speed = if busy_delta > 0.0001 { emulated_delta / busy_delta } else { 0.0 };
            // Express CPU demand as the percentage of one worker thread needed to sustain 1x.
            // Above 100% means no audio buffer can make this device keep up indefinitely.
            let required_load = if speed > 0.001 { 100.0 / speed } else { 999.0 };
            if let Ok(mut current) = status.lock() {
                current.emulated_ms = emulated_ms;
                current.speed = speed;
                current.required_load_percent = required_load;
                current.underruns = underruns.load(Ordering::Relaxed);
                current.firmware_starved_halves = machine.soc.refill.starved;
                current.halted = machine.halted;
                current.clock_mhz = (machine.soc.periph.hz / 1_000_000) as u32;
                current.jit_state = machine.cpu.native_jit_status();
            }

            let new_underruns = underruns.load(Ordering::Relaxed);
            if new_underruns > previous_underruns {
                target = (target + TARGET_STEP_FRAMES).min(TARGET_MAX_FRAMES);
                calm_since = now;
                previous_underruns = new_underruns;
            } else if target > TARGET_MIN_FRAMES && now.duration_since(calm_since) >= Duration::from_secs(10) {
                let reduction = ((target - TARGET_MIN_FRAMES) / 4).max(1);
                target = (target - reduction).max(TARGET_MIN_FRAMES);
                calm_since = now;
            }
            previous_ms = emulated_ms;
            previous_busy = total_busy;
            report_at = now;
        }
    }

}

/// Create the emulator. `saved_flash` is either null/zero length or exactly the chip's 1 MiB image.
#[no_mangle]
pub unsafe extern "C" fn fm1_android_create(
    package: *const u8,
    package_len: usize,
    saved_flash: *const u8,
    saved_flash_len: usize,
    clock_mhz: u32,
    error: *mut c_char,
    error_capacity: usize,
) -> *mut Engine {
    if package.is_null() || package_len == 0 {
        write_error(error, error_capacity, "No firmware package was provided");
        return ptr::null_mut();
    }
    let package = std::slice::from_raw_parts(package, package_len);
    let saved = if !saved_flash.is_null() && saved_flash_len == FLASH_SIZE {
        Some(std::slice::from_raw_parts(saved_flash, saved_flash_len))
    } else { None };
    match std::panic::catch_unwind(|| Engine::new(package, saved, clock_mhz)) {
        Ok(Ok(engine)) => Box::into_raw(Box::new(engine)),
        Ok(Err(message)) => { write_error(error, error_capacity, &message); ptr::null_mut() }
        Err(_) => { write_error(error, error_capacity, "The emulator panicked while loading this firmware"); ptr::null_mut() }
    }
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_destroy(engine: *mut Engine) {
    if !engine.is_null() { drop(Box::from_raw(engine)); }
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_render_audio(engine: *mut Engine, output: *mut f32, frames: usize) -> usize {
    if engine.is_null() || output.is_null() { return 0; }
    let engine = &*engine;
    let destination = std::slice::from_raw_parts_mut(output, frames.saturating_mul(2));
    engine.render_audio(destination, frames)
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_render_panel(engine: *mut Engine, output: *mut u32, pixels: usize) -> i32 {
    if engine.is_null() || output.is_null() { return 0; }
    let engine = &*engine;
    let destination = std::slice::from_raw_parts_mut(output, pixels);
    match engine.render_panel(destination) {
        Ok((w, h)) => (w * h) as i32,
        Err(_) => 0,
    }
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_panel_size(engine: *mut Engine, width: *mut u32, height: *mut u32) {
    if engine.is_null() { return; }
    let (w, h) = (*engine).panel_size();
    if !width.is_null() { *width = w as u32; }
    if !height.is_null() { *height = h as u32; }
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_panel_hit(engine: *mut Engine, x: f32, y: f32) -> i32 {
    if engine.is_null() { return -1; }
    (*engine).panel_hit(x, y)
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_panel_hit_y(engine: *mut Engine) -> f32 {
    if engine.is_null() { return 0.0; }
    f32::from_bits((*engine).hit_y_bits.load(Ordering::Relaxed))
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_panel_slide(engine: *mut Engine, index: u32, y: f32) -> u32 {
    if engine.is_null() { return 0; }
    (*engine).panel_slide(index, y)
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_key(engine: *mut Engine, id: u32, down: u32, velocity: u32) {
    if engine.is_null() || id > u8::MAX as u32 { return; }
    let _ = (*engine).commands.send(Command::Key { id: id as u8, down: down != 0, velocity });
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_encoder(engine: *mut Engine, panel_index: u32, clicks: i32) {
    if engine.is_null() || panel_index >= 8 || clicks == 0 { return; }
    let _ = (*engine).commands.send(Command::Encoder { index: panel_index as u8, clicks });
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_analog(engine: *mut Engine, index: u32, value: u32) {
    if engine.is_null() || index >= 23 { return; }
    let _ = (*engine).commands.send(Command::Analog { index: index as u8, value: value.min(1023) as u16 });
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_pot(engine: *mut Engine, value: u32) {
    if engine.is_null() { return; }
    let _ = (*engine).commands.send(Command::Pot(value.min(1023) as u16));
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_set_theme(engine: *mut Engine, theme: u32) {
    if engine.is_null() { return; }
    (*engine).theme.store((theme as usize).min(panel::THEMES.len().saturating_sub(1)), Ordering::Relaxed);
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_theme_count() -> u32 { panel::THEMES.len() as u32 }

#[no_mangle]
pub unsafe extern "C" fn fm1_android_theme_name(index: u32, output: *mut c_char, capacity: usize) -> usize {
    if output.is_null() || capacity == 0 { return 0; }
    let label = panel::THEMES.get(index as usize).map_or("Default", |entry| entry.1);
    let bytes = label.as_bytes();
    let count = bytes.len().min(capacity - 1);
    ptr::copy_nonoverlapping(bytes.as_ptr(), output as *mut u8, count);
    *output.add(count) = 0;
    count
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_status(engine: *mut Engine, output: *mut c_char, capacity: usize) -> usize {
    if engine.is_null() || output.is_null() || capacity == 0 { return 0; }
    let status = (*engine).status();
    let text = format!(
        "{{\"clock\":{},\"emulatedMs\":{},\"speed\":{:.3},\"load\":{:.1},\"underruns\":{},\"starvedHalves\":{},\"jit\":{},\"halted\":{}}}",
        status.clock_mhz, status.emulated_ms, status.speed, status.required_load_percent,
        status.underruns, status.firmware_starved_halves, status.jit_state, status.halted
    );
    copy_c_string(&text, output, capacity)
}

#[no_mangle]
pub unsafe extern "C" fn fm1_android_export_flash(engine: *mut Engine, output: *mut u8, capacity: usize) -> usize {
    if engine.is_null() || output.is_null() { return 0; }
    (*engine).export_flash(std::slice::from_raw_parts_mut(output, capacity))
}

unsafe fn write_error(output: *mut c_char, capacity: usize, message: &str) {
    if output.is_null() || capacity == 0 { return; }
    let _ = copy_c_string(message, output, capacity);
}

unsafe fn copy_c_string(value: &str, output: *mut c_char, capacity: usize) -> usize {
    if output.is_null() || capacity == 0 { return 0; }
    let count = value.len().min(capacity - 1);
    ptr::copy_nonoverlapping(value.as_ptr(), output as *mut u8, count);
    *output.add(count) = 0;
    count
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adaptive_target_is_inside_supported_bounds() {
        assert!(TARGET_MIN_FRAMES < TARGET_INITIAL_FRAMES);
        assert!(TARGET_INITIAL_FRAMES < TARGET_MAX_FRAMES);
        assert!(TARGET_STEP_FRAMES > 0);
    }
}
