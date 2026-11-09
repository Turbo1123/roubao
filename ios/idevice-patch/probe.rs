// Screenshot + touch + hardware buttons over CoreDevice, for an agent driving
// its own phone. Each call opens a fresh service connection on the existing
// tunnel: the capture service drops its connection after a shot or two.
// iOS 27 drops HID input unless a DisplayService media stream is open, so on
// 27 every touch/button/type call brackets itself with one (see `open_gate`).

use std::ptr::null_mut;
use std::time::Duration;

use idevice::core_device::hid::{ButtonState, IndigoHidClient, KeyboardUsage, MainKeyboardService, UniversalHidServiceClient};
use idevice::core_device::{
    CallInfoBlob, DisplayServiceClient, ImageFormat, ScreenCaptureServiceClient, build_screen_audio_offer,
    build_screen_video_offer, build_start_audio_parameters, build_start_video_parameters,
    PasteboardServiceClient, GENERAL_PASTEBOARD,
};
use idevice::tcp::handle::UdpSocketHandle;
use std::sync::atomic::{AtomicU8, Ordering};
use idevice::{IdeviceError, RsdService as _};

use crate::{IdeviceFfiError, core_device_proxy::AdapterHandle, ffi_err, rsd::RsdHandshakeHandle, run_sync_local};

/// Captures the main display. `jpeg` != 0 asks for JPEG instead of PNG.
/// Free `out_data` with `idevice_data_free(out_data, out_len)`.
///
/// # Safety
/// All pointers must be valid; handles must come from this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_screenshot(
    adapter: *mut AdapterHandle,
    handshake: *mut RsdHandshakeHandle,
    jpeg: u8,
    out_data: *mut *mut u8,
    out_len: *mut usize,
) -> *mut IdeviceFfiError {
    if adapter.is_null() || handshake.is_null() || out_data.is_null() || out_len.is_null() {
        return ffi_err!(IdeviceError::FfiInvalidArg);
    }
    let format = if jpeg != 0 { ImageFormat::Jpeg } else { ImageFormat::Png };
    let res = run_sync_local(async move {
        let (a, h) = unsafe { (&mut (*adapter).0, &mut (*handshake).0) };
        let mut client = ScreenCaptureServiceClient::connect_rsd(a, h).await?;
        client.take_screenshot(None, format).await
    });
    match res {
        Ok(image) => {
            let image = image.into_boxed_slice();
            unsafe {
                *out_len = image.len();
                *out_data = Box::into_raw(image) as *mut u8;
            }
            null_mut()
        }
        Err(e) => ffi_err!(e),
    }
}

/// Touch drag from (x1, y1) to (x2, y2), normalized 0..65535. Equal points = a tap,
/// held for `hold_ms` (a long press when ~800). A drag first holds still for `hold_ms`.
///
/// # Safety
/// Handles must be valid and come from this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_touch(
    adapter: *mut AdapterHandle,
    handshake: *mut RsdHandshakeHandle,
    x1: u16,
    y1: u16,
    x2: u16,
    y2: u16,
    hold_ms: u64,
) -> *mut IdeviceFfiError {
    if adapter.is_null() || handshake.is_null() {
        return ffi_err!(IdeviceError::FfiInvalidArg);
    }
    let res = run_sync_local(async move {
        let (a, h) = unsafe { (&mut (*adapter).0, &mut (*handshake).0) };
        let gate = open_gate(a, h).await;
        let mut hid = UniversalHidServiceClient::connect_rsd(a, h).await?;
        // Stamp reports with this phone's own clock: idevice's default (wall
        // clock) gets drags through but most taps dropped.
        let ts = || Some(unsafe { libc::mach_absolute_time() });
        if (x1, y1) == (x2, y2) {
            hid.send_touchscreen(TOUCH_CONTACT, x1, y1, ts()).await?;
            tokio::time::sleep(Duration::from_millis(hold_ms.max(60))).await;
            hid.send_touchscreen(TOUCH_RELEASE, x1, y1, ts()).await?;
            hid.send_touchscreen(TOUCH_RELEASE, x1, y1, ts()).await?;
        } else {
            if hold_ms > 0 {
                hid.send_touchscreen(TOUCH_CONTACT, x1, y1, ts()).await?;
                tokio::time::sleep(Duration::from_millis(hold_ms)).await;
            }
            for i in 0..=30u32 {
                let t = i as f64 / 30.0;
                let x = (x1 as f64 + (x2 as f64 - x1 as f64) * t).round() as u16;
                let y = (y1 as f64 + (y2 as f64 - y1 as f64) * t).round() as u16;
                hid.send_touchscreen(TOUCH_CONTACT, x, y, ts()).await?;
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            hid.send_touchscreen(TOUCH_RELEASE, x2, y2, ts()).await?;
            hid.send_touchscreen(TOUCH_RELEASE, x2, y2, ts()).await?;
        }
        // dtuhidd handles reports async and the userspace TCP stack flushes
        // lazily; closing early loses the release, which turns taps into presses.
        tokio::time::sleep(Duration::from_millis(500)).await;
        close_gate(gate).await;
        Ok::<(), IdeviceError>(())
    });
    match res {
        Ok(()) => null_mut(),
        Err(e) => ffi_err!(e),
    }
}

/// Presses a hardware button (HID usage page/code, e.g. home = 0x0C/0x40) for `hold_ms`.
///
/// # Safety
/// Handles must be valid and come from this library.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_button(
    adapter: *mut AdapterHandle,
    handshake: *mut RsdHandshakeHandle,
    usage_page: u64,
    usage_code: u64,
    hold_ms: u64,
) -> *mut IdeviceFfiError {
    if adapter.is_null() || handshake.is_null() {
        return ffi_err!(IdeviceError::FfiInvalidArg);
    }
    let res = run_sync_local(async move {
        let (a, h) = unsafe { (&mut (*adapter).0, &mut (*handshake).0) };
        let gate = open_gate(a, h).await;
        let mut hid = IndigoHidClient::connect_rsd(a, h).await?;
        hid.send_button(usage_page, usage_code, ButtonState::Down).await?;
        tokio::time::sleep(Duration::from_millis(hold_ms)).await;
        hid.send_button(usage_page, usage_code, ButtonState::Up).await?;
        tokio::time::sleep(Duration::from_millis(500)).await; // flush, see probe_touch
        close_gate(gate).await;
        Ok::<(), IdeviceError>(())
    });
    match res {
        Ok(()) => null_mut(),
        Err(e) => ffi_err!(e),
    }
}

const TOUCH_CONTACT: u8 = 0xC2;
const TOUCH_RELEASE: u8 = 0x02;

/// 0 = not tried yet, 1 = device needs the stream (iOS 27+), 2 = it refuses one (older iOS).
static GATE_MODE: AtomicU8 = AtomicU8::new(0);

type Gate = Option<(DisplayServiceClient<Box<dyn idevice::ReadWrite>>, UdpSocketHandle, UdpSocketHandle)>;

/// Starts the audio+video screen-sharing session that holds backboardd's HID
/// auth gate open. Mirrors tools/src/coredevice_stream.rs; the RTP is never read.
async fn open_gate(
    adapter: &mut idevice::tcp::handle::AdapterHandle,
    handshake: &mut idevice::rsd::RsdHandshake,
) -> Gate {
    if GATE_MODE.load(Ordering::Relaxed) == 2 {
        return None;
    }
    let res: Result<_, IdeviceError> = async {
        let mut client = DisplayServiceClient::connect_rsd(adapter, handshake).await?;
        let audio_udp = adapter.bind_udp(0).await?;
        let video_udp = adapter.bind_udp(0).await?;
        let (host, peer) = (adapter.host_ip().to_string(), adapter.peer_ip().to_string());
        let call_info = CallInfoBlob {
            call_id: 0,
            client_version: 1,
            device_type: "Mac17,7".into(),
            framework_version: "2205.3.1".into(),
            os_version: "25F71".into(),
            device_name: None,
            audio_device_uid: None,
        };
        let session = uuid::Uuid::new_v4();
        let call_id = || uuid::Uuid::new_v4().to_string().to_uppercase();
        let audio_offer = build_screen_audio_offer(&call_id(), &call_info)?;
        client
            .start_media_stream(build_start_audio_parameters(
                &host, audio_udp.local_port(), &peer, 50000, audio_offer, 140, session,
            ))
            .await?;
        let video_offer = build_screen_video_offer(&call_id(), &call_info, uuid::Uuid::new_v4().as_u128() as u32)?;
        client
            .start_media_stream(build_start_video_parameters(
                &host, video_udp.local_port(), &peer, 50001, video_offer, 140, 1, session,
            ))
            .await?;
        Ok((client, audio_udp, video_udp))
    }
    .await;
    match res {
        Ok(gate) => {
            GATE_MODE.store(1, Ordering::Relaxed);
            // backboardd needs a moment to re-match HID surfaces to the stream.
            tokio::time::sleep(Duration::from_millis(300)).await;
            Some(gate)
        }
        Err(e) => {
            // Pre-27 devices reject remote control (9021) and don't need it.
            if GATE_MODE.load(Ordering::Relaxed) == 0 {
                tracing::info!("no HID media stream, continuing without: {e:?}");
                GATE_MODE.store(2, Ordering::Relaxed);
            }
            None
        }
    }
}

async fn close_gate(gate: Gate) {
    if let Some((mut client, _audio, _video)) = gate {
        let _ = client.stop_media_stream().await;
    }
}

// A complete held-key report is needed for shortcuts. Separate Indigo single-key
// events are not a reliable representation of Command + another held key.
async fn shortcut(
    hid: &mut UniversalHidServiceClient<Box<dyn idevice::ReadWrite>>,
    keyboard: &mut MainKeyboardService,
    key: u16,
    command: bool,
) -> Result<(), IdeviceError> {
    let key = KeyboardUsage::new(key).map_err(keyboard_error)?;
    let modifier = KeyboardUsage::new(0xE3).map_err(keyboard_error)?;
    let states = if command {
        vec![vec![modifier], vec![modifier, key], vec![modifier], vec![]]
    } else { vec![vec![key], vec![]] };
    for pressed in states {
        hid.set_main_keyboard_usages(keyboard, pressed).await.map_err(keyboard_error)?;
        tokio::time::sleep(Duration::from_millis(60)).await;
    }
    Ok(())
}

fn keyboard_error(error: impl std::fmt::Display) -> IdeviceError {
    IdeviceError::UnexpectedResponse(format!("text input keyboard: {error}; inspect screen before retry"))
}

/// Writes UTF-8 text through CoreDevice, verifies ONLY the clipboard, then pastes
/// once into the focused field. `replace` selects all before pasting. Never retries
/// a submitted paste. The caller must separately inspect the actual field.
///
/// # Safety
/// Handles must be valid and `text` must be a valid NUL-terminated UTF-8 string.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_insert_text(
    adapter: *mut AdapterHandle,
    handshake: *mut RsdHandshakeHandle,
    text: *const std::ffi::c_char,
    replace: u8,
) -> *mut IdeviceFfiError {
    if adapter.is_null() || handshake.is_null() || text.is_null() || replace > 1 {
        return ffi_err!(IdeviceError::FfiInvalidArg);
    }
    let Ok(text) = unsafe { std::ffi::CStr::from_ptr(text) }.to_str() else {
        return ffi_err!(IdeviceError::FfiInvalidString);
    };
    let res = run_sync_local(async move {
        let (a, h) = unsafe { (&mut (*adapter).0, &mut (*handshake).0) };
        let gate = open_gate(a, h).await;
        let result = async {
            let mut clipboard = PasteboardServiceClient::connect_rsd(a, h).await?;
            let mut hid = UniversalHidServiceClient::connect_rsd(a, h).await?;
            // Discover/confirm the keyboard before replacing the user's clipboard.
            let mut keyboard = hid.create_main_keyboard().await.map_err(keyboard_error)?;
            // UIKit attaches a newly created hardware keyboard asynchronously.
            // Its first shortcut can otherwise be dropped (notably Select All).
            tokio::time::sleep(Duration::from_millis(500)).await;
            let input = async {
                clipboard.set_text(text, GENERAL_PASTEBOARD).await?;
                let mut verified = false;
                for _ in 0..3 {
                    if clipboard.get(GENERAL_PASTEBOARD).await?.text().as_deref() == Some(text) {
                        verified = true;
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(60)).await;
                }
                if !verified {
                    return Err(IdeviceError::UnexpectedResponse("clipboard verification failed; paste not submitted".into()));
                }
                if replace != 0 {
                    shortcut(&mut hid, &mut keyboard, 0x04, true).await?;
                    tokio::time::sleep(Duration::from_millis(500)).await;
                }
                shortcut(&mut hid, &mut keyboard, 0x19, true).await?;
                tokio::time::sleep(Duration::from_millis(500)).await;
                Ok(())
            }.await;
            // Always release/reset/remove our keyboard, including failed writes.
            let cleanup = hid.remove_main_keyboard(&mut keyboard).await.map_err(keyboard_error);
            input.and(cleanup)
        }.await;
        close_gate(gate).await;
        result
    });
    match res { Ok(()) => null_mut(), Err(e) => ffi_err!(e) }
}

/// One non-text key (Return, Backspace or Escape). Does not touch the clipboard.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_edit_key(
    adapter: *mut AdapterHandle, handshake: *mut RsdHandshakeHandle, usage: u16,
) -> *mut IdeviceFfiError {
    if adapter.is_null() || handshake.is_null() || ![0x28, 0x2A, 0x29].contains(&usage) {
        return ffi_err!(IdeviceError::FfiInvalidArg);
    }
    let res = run_sync_local(async move {
        let (a, h) = unsafe { (&mut (*adapter).0, &mut (*handshake).0) };
        let gate = open_gate(a, h).await;
        let result = async {
            let mut hid = UniversalHidServiceClient::connect_rsd(a, h).await?;
            let mut keyboard = hid.create_main_keyboard().await.map_err(keyboard_error)?;
            tokio::time::sleep(Duration::from_millis(500)).await;
            let input = shortcut(&mut hid, &mut keyboard, usage, false).await;
            tokio::time::sleep(Duration::from_millis(500)).await;
            let cleanup = hid.remove_main_keyboard(&mut keyboard).await.map_err(keyboard_error);
            input.and(cleanup)
        }.await;
        close_gate(gate).await;
        result
    });
    match res { Ok(()) => null_mut(), Err(e) => ffi_err!(e) }
}

/// Prepare and verify synthetic clipboard content without sending a paste key.
/// Used by the opt-in permission probe to separate reading from HID user intent.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn probe_stage_text(
    adapter: *mut AdapterHandle, handshake: *mut RsdHandshakeHandle,
    text: *const std::ffi::c_char,
) -> *mut IdeviceFfiError {
    if adapter.is_null() || handshake.is_null() || text.is_null() {
        return ffi_err!(IdeviceError::FfiInvalidArg);
    }
    let Ok(text) = unsafe { std::ffi::CStr::from_ptr(text) }.to_str() else {
        return ffi_err!(IdeviceError::FfiInvalidString);
    };
    let result = run_sync_local(async move {
        let (a, h) = unsafe { (&mut (*adapter).0, &mut (*handshake).0) };
        let mut client = PasteboardServiceClient::connect_rsd(a, h).await?;
        client.set_text(text, GENERAL_PASTEBOARD).await?;
        if client.get(GENERAL_PASTEBOARD).await?.text().as_deref() != Some(text) {
            return Err(IdeviceError::UnexpectedResponse("probe clipboard readback failed".into()));
        }
        Ok::<(), IdeviceError>(())
    });
    match result { Ok(()) => null_mut(), Err(e) => ffi_err!(e) }
}
