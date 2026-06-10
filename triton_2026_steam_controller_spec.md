# Specification: Triton Controller (2026 Steam Controller)

This document specifies the technical design, protocol characteristics, button layouts, and advanced capabilities of the **Triton Controller (2026 Steam Controller)**, based on the reverse-engineered native libraries and Android Java driver implementation.

---

## 1. Hardware Overview & Connection Modes

The Triton Controller is the 2026 revision of Valve's gaming controller, designed for native integration with Steam Link and Moonlight streaming platforms.

* **Vendor ID (VID)**: `0x28de` (Valve Corporation)
* **Bluetooth LE Product ID (PID)**: `0x1303`
* **USB Wired / Wireless Dongle PIDs**: `0x1304` (wired interface) & `0x1305` (dongle wireless interface)
* **GATT Service UUID**: `100F6C32-1735-4313-B402-38567131E5F3`
* **BLE Input Characteristic UUIDs**:
  * `100F6C7A-1735-4313-B402-38567131E5F3` (Handles packet profile `0x45` reports)
  * `100F6C7C-1735-4313-B402-38567131E5F3` (Handles packet profile `0x47` reports)

### Connection Initialization (BLE vs 2015 D0G)
Unlike the original 2015 Steam Controller (D0G), which starts in "Lizard Mode" and requires a specialized vendor command sequence (`enterValveMode`: `0xC0 0x87 0x03 0x08 0x07 0x00`) to be written to the report characteristic before streaming reports, **Triton does not require any enter-mode commands**. Once paired and connected over BLE, it directly streams native input reports. Triton requests a large MTU size of **`517`** bytes to support Data Length Extension (DLE) for higher report rates and sensor precision.

---

## 2. Input Report Packet Layout

Under BLE, reports are delivered directly through notifications. The first byte acts as a rolling sequence counter, followed by the button masks and axis values.

### Packet Structure
```cpp
#pragma pack(push, 1)
struct TritonInputReport {
    uint8_t  sequence_counter; // Byte 0: Rolling sequence counter
    uint32_t buttons;          // Bytes 1-4: 32-bit button bitmask (only 20 bits active)
    uint16_t left_trigger;     // Bytes 5-6: Analog left trigger (0 to 65535)
    uint16_t right_trigger;    // Bytes 7-8: Analog right trigger (0 to 65535)
    int16_t  left_stick_x;     // Bytes 9-10: Left stick X axis
    int16_t  left_stick_y;     // Bytes 11-12: Left stick Y axis (negated)
    int16_t  right_stick_x;    // Bytes 13-14: Right stick X axis
    int16_t  right_stick_y;    // Bytes 15-16: Right stick Y axis (negated)
    
    // Bytes 17+ contain Gyro / Accelerometer sensor payload:
    // - For report type 'B'/'E': offsets 17-52 (36 bytes)
    // - For report type 'G': offsets 17-44 (28 bytes)
};
#pragma pack(pop)
```

---

## 3. Button Layout & Mappings

The Triton controller supports **20 digital buttons**. In the C++ native layer (`libmain.so`), raw button bits `0` to `19` are mapped to standard **SDL3 Gamepad Buttons** via a static lookup table. In the Java driver, these are translated into `ControllerPacket` flags.

| Raw Bit Index | Byte / Bit Mask | Physical Button | C++ SDL3 Mapping | Java `ControllerPacket` Mapping |
| :---: | :--- | :--- | :--- | :--- |
| **0** | `b0 & 0x01` | **A** | `SDL_GAMEPAD_BUTTON_SOUTH` | `ControllerPacket.A_FLAG` |
| **1** | `b0 & 0x02` | **B** | `SDL_GAMEPAD_BUTTON_EAST` | `ControllerPacket.B_FLAG` |
| **2** | `b0 & 0x04` | **X** | `SDL_GAMEPAD_BUTTON_WEST` | `ControllerPacket.X_FLAG` |
| **3** | `b0 & 0x08` | **Y** | `SDL_GAMEPAD_BUTTON_NORTH` | `ControllerPacket.Y_FLAG` |
| **4** | `b0 & 0x10` | **Share** | `SDL_GAMEPAD_BUTTON_MISC1` | `ControllerPacket.MISC_FLAG` |
| **5** | `b0 & 0x20` | **RS Click** | `SDL_GAMEPAD_BUTTON_RIGHT_STICK` | `ControllerPacket.RS_CLK_FLAG` |
| **6** | `b0 & 0x40` | **Start / Menu** | `SDL_GAMEPAD_BUTTON_START` | `ControllerPacket.PLAY_FLAG` |
| **7** | `b0 & 0x80` | **Right Paddle 1** | `SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1` | `ControllerPacket.PADDLE1_FLAG` |
| **8** | `b1 & 0x01` | **Right Paddle 2** | `SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2` | `ControllerPacket.PADDLE3_FLAG` |
| **9** | `b1 & 0x02` | **RB** | `SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER`| `ControllerPacket.RB_FLAG` |
| **10** | `b1 & 0x04` | **DPAD Down** | `SDL_GAMEPAD_BUTTON_DPAD_DOWN` | `ControllerPacket.DOWN_FLAG` |
| **11** | `b1 & 0x08` | **DPAD Right** | `SDL_GAMEPAD_BUTTON_DPAD_RIGHT` | `ControllerPacket.RIGHT_FLAG` |
| **12** | `b1 & 0x10` | **DPAD Left** | `SDL_GAMEPAD_BUTTON_DPAD_LEFT` | `ControllerPacket.LEFT_FLAG` |
| **13** | `b1 & 0x20` | **DPAD Up** | `SDL_GAMEPAD_BUTTON_DPAD_UP` | `ControllerPacket.UP_FLAG` |
| **14** | `b1 & 0x40` | **Back / Options**| `SDL_GAMEPAD_BUTTON_BACK` | `ControllerPacket.BACK_FLAG` |
| **15** | `b1 & 0x80` | **LS Click** | `SDL_GAMEPAD_BUTTON_LEFT_STICK` | `ControllerPacket.LS_CLK_FLAG` |
| **16** | `b2 & 0x01` | **Guide (Steam)** | `SDL_GAMEPAD_BUTTON_GUIDE` | `ControllerPacket.SPECIAL_BUTTON_FLAG`|
| **17** | `b2 & 0x02` | **Left Paddle 1** | `SDL_GAMEPAD_BUTTON_LEFT_PADDLE1` | `ControllerPacket.PADDLE2_FLAG` |
| **18** | `b2 & 0x04` | **Left Paddle 2** | `SDL_GAMEPAD_BUTTON_LEFT_PADDLE2` | `ControllerPacket.PADDLE4_FLAG` |
| **19** | `b2 & 0x08` | **LB** | `SDL_GAMEPAD_BUTTON_LEFT_SHOULDER` | `ControllerPacket.LB_FLAG` |

### Capacitive Touch, Haptic Clicks, & Trigger Presses

Triton includes capacitive touch sensors, physical trackpad click switches, and physical trigger press switches mapped to the upper bits of the 32-bit button mask. When contact is made/broken or physical switches are actuated/released, these transitions act as digital button events.

| Raw Bit Index | Byte / Bit Mask | Sensor Name | Description | ON Hex Code | OFF Hex Code |
| :---: | :--- | :--- | :--- | :--- | :--- |
| **20** | `b2 & 0x10` | **Right Stick Touch** | Capacitive touch on Right Thumbstick | `0x00100000` | `0x00000000` |
| **21** | `b2 & 0x20` | **Right Trackpad Touch** | Capacitive touch (`trackpad_Z_on`) on Right Trackpad | `0x00200000` | `0x00000000` |
| **22** | `b2 & 0x40` | **Right Trackpad Click** | Physical click on Right Trackpad | `0x00400000` | `0x00000000` |
| **23** | `b2 & 0x80` | **Right Trigger Click** | Physical press on Right Trigger | `0x00800000` | `0x00000000` |
| **24** | `b3 & 0x01` | **Left Stick Touch** | Capacitive touch on Left Thumbstick | `0x01000000` | `0x00000000` |
| **25** | `b3 & 0x02` | **Left Trackpad Touch** | Capacitive touch (`trackpad_Z_on`) on Left Trackpad | `0x02000000` | `0x00000000` |
| **26** | `b3 & 0x04` | **Left Trackpad Click** | Physical click on Left Trackpad | `0x04000000` | `0x00000000` |
| **27** | `b3 & 0x08` | **Left Trigger Click** | Physical press on Left Trigger | `0x08000000` | `0x00000000` |
| **28** | `b3 & 0x10` | **Right Grip Touch** | Capacitive touch on Right Grip | `0x10000000` | `0x00000000` |
| **29** | `b3 & 0x20` | **Left Grip Touch** | Capacitive touch on Left Grip | `0x20000000` | `0x00000000` |

---

## 4. Gyroscope & Accelerometer (IMU)

Triton is equipped with a high-performance **6-axis IMU** (3-axis accelerometer and 3-axis gyroscope) to support motion-assisted aiming and gestures.

* **Sensor Data Formats**: Accelerometer and Gyroscope readings are packaged as signed 16-bit integers (`int16_t`).
* **Packet Routing & ID**:
  * Raw sensor payloads are populated inside `'B'` (0x42), `'E'` (0x45), or `'G'` (0x47) reports.
  * Over BLE, sensor data starts at byte index `17` of the packet.
  * For report types `'B'` and `'E'`, sensor fields occupy bytes 17–52.
  * For report type `'G'`, sensor fields occupy bytes 17–44.
* **Sensor Clearing**:
  If the host disables motion tracking or the controller is undergoing calibration, the native routing layer triggers `ClearSensorDataTritonController` to zero out the sensor bytes:
  * For `'B'`/`'E'` reports, offsets `0x1c` (8 bytes), `0x24` (8 bytes), `0x2c` (2 bytes), and `0x2e` (8 bytes - only for `'B'`) are cleared.
  * For `'G'` reports, offsets `0x1e` (8 bytes) and `0x26` (8 bytes) are cleared.

---

## 5. Rumble & Haptics Support

Triton features dual **Linear Resonant Actuators (LRAs)** instead of traditional ERM motors. These LRAs provide HD haptic feedback, reproducing subtle textures as well as intense vibrations.

* **Rumble Emulation**: Standard low-frequency and high-frequency rumble commands sent by the host (GFE/Sunshine) are parsed by the client. The driver maps these to standard Bluetooth HID Output Reports sent to Triton's command characteristic, which Triton's internal firmware translates into LRA vibration patterns.
* **Trigger Rumble**: Triton supports trigger-specific rumble (independent haptic feedback on the analog triggers).
* **Capacitive Touch Activation**: Triton features capacitive touch sensors on the trackpads, sticks, and grips ("Grip Sense"). Haptic "clicks" or textures are generated locally by the controller when touch events are detected, simulating mechanical buttons.

---

## 6. Identify Controller / Ping Feature

To assist users in identifying which physical controller is active (useful in multi-controller local co-op setups), Triton supports an **Identify/Ping** feature.

* **Triggering**: When the user requests to identify the controller in the UI, Moonlight writes a specific vendor haptic command packet to Triton's output characteristic.
* **Response**: Rather than a basic continuous vibration, Triton's LRAs are driven to play a short, distinctive audio/haptic chirp pattern (using frequency sweeps) to acoustically and tactilely alert the user.

---

## 7. Battery Status Reporting

Triton implements standard Bluetooth SIG specifications alongside custom telemetry for reliable battery monitoring.

1. **Standard GATT Battery Service**: Triton exposes the GATT Battery Service UUID `0x180F` with the Battery Level Characteristic `0x2A19`. This returns a direct, read-only integer value between `0` and `100` representing the current charge percentage.
2. **Voltage Telemetry**: Inside system status reports, Triton sends raw battery voltage data (measured in millivolts). This telemetry allows drivers to compute precise battery life curves and fire OS-level low-battery notifications when voltage thresholds are crossed.

---

## 8. Dual Trackpads

Triton inherits and refines the signature input layout of the Steam Deck, featuring **dual square trackpads** situated on the left and right sides of the controller.

* **Touch & Coordinate Tracking**:
  * The trackpads detect capacitive contact and report high-precision raw coordinates.
  * When touch-tracking is active, standard reports include X and Y coordinates formatted as signed 16-bit integers (`int16_t`) representing absolute touch positions along the axes.
  * Touch contact states (i.e. whether a finger is touching the surface) are mapped as digital touch flags.
* **Haptic Trackball & Scroll Emulation**:
  * The trackpads are physically backed by LRA haptic actuators.
  * Host drivers can enable internal emulation algorithms (such as virtual trackball friction, mouse scrolls, or radial virtual menus) where the LRA pulses simulate physical momentum, clicks, and detents beneath the user's thumb.

---

## 9. Status & Tracking LEDs

Triton features integrated **Light Emitting Diodes (LEDs)** embedded on the controller face. These LEDs serve two main purposes:

1. **Status & Player Indicator**:
   * The LEDs display device power state, Bluetooth pairing animations, and battery level alerts (e.g. low-battery slow pulsing).
   * In local multiplayer environments, the LEDs serve as player slot indicators (assigning unique visual indicator colors to Player 1, Player 2, etc.).
2. **Spatial / VR Headset Tracking**:
   * The LEDs are designed to be visually trackable by external VR hardware (such as Valve's "Steam Frame" headset) to provide precise 6-DOF controller tracking in mixed-reality and virtual-reality settings.
   * **Host Control**: The host controls the LED states (color, brightness, duty cycle, and pulse modulation) by writing vendor-specific HID Output Report packets directly to the controller's write characteristic.
