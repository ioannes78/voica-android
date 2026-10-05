# Stage 13B B1 — Background playback / BLE session real-device QA

Status: **CANDIDATE CHECKLIST**

Branch: `stage13b-stability-background-longrecording`

Candidate scope: Stage 13B.2 background execution boundary + Stage 13B.3 first connected-device ownership layer.

This candidate intentionally does **not** include model-install background orchestration, ASR/diarization media-processing integration, or the final 30/60/120 minute stress campaign.

## 1. Preconditions

- Use a QS668 / CB08 recorder that already passed the Stage 13A device baseline.
- Bluetooth permissions granted.
- For Android 13+, notification permission should be granted when available so connected-device progress can be inspected in the notification shade. Media playback controls remain tied to the MediaSession.
- Keep at least one downloaded local recording available for playback.
- Keep at least one device recording file available for download.

## 2. Background / lock-screen playback

1. Open a local recording and start playback.
2. Confirm normal in-app waveform, seek and speed controls still work.
3. Press Home while audio is playing.
4. Confirm playback continues without restarting from zero.
5. Open notification shade and verify the Voica media notification is present.
6. Use notification / system media controls to pause and resume.
7. Use the 10-second rewind/forward controls.
8. Lock the phone while playback is active.
9. Confirm playback continues through lock screen.
10. Pause/resume from lock-screen media controls.
11. Unlock and return to Voica.
12. Confirm the in-app player is attached to the same session and position, with no second playback stream.
13. Change speed in-app and repeat Home/lock/unlock.

Pass criteria:

- no background auto-pause merely because the Activity stopped;
- no duplicate player/audio stream;
- no seek reset or timeline jump;
- system controls act on the same in-app session;
- no crash/ANR;
- Audio Focus behavior remains normal.

## 3. Playback / recorder interlock

1. Start local playback.
2. While playback is active, start recording on the QS668 / CB08 (device button and App command should each be tested once).
3. Confirm local playback pauses when device recording becomes active.
4. Confirm the recorder continues recording normally.
5. Stop/save the device recording.

Pass criteria:

- recording remains higher priority than playback;
- no simultaneous Voica local playback that destabilizes device recording;
- no automatic playback restart unless explicitly requested by the user.

## 4. Connected recorder: Home / lock screen

1. Connect the recorder and wait for Ready.
2. Press Home for at least 60 seconds.
3. Return to Voica and confirm connection/device information remains coherent.
4. Start device recording.
5. Press Home while recording.
6. Lock the phone for at least 60 seconds.
7. Unlock and return.
8. Confirm recording status, elapsed duration and filename/size reconcile to the actual device state.
9. Pause/resume recording and repeat one Home/lock cycle.
10. Save recording and confirm the new device file appears after refresh/reconciliation.

Pass criteria:

- leaving the Activity no longer intentionally stops recording polling/reconnect merely because the UI is backgrounded;
- no ghost Recording state;
- no duplicate connection session;
- device remains the source of truth after reconnect/foreground return.

## 5. Remote disconnect / reconnect

1. Connect recorder and start a recording.
2. Create a temporary recoverable disconnect (move out of range or otherwise interrupt BLE without manually pressing Disconnect in Voica).
3. Return the device to range.
4. Confirm the App reconnects and reconciles the true device recording state.
5. Repeat once while the phone is locked/backgrounded.

Pass criteria:

- reconnect does not storm;
- recording on the physical device is not falsely shown as stopped/complete;
- recovered state matches device truth.

## 6. Explicit user disconnect

1. Connect the recorder.
2. Use Voica's explicit Disconnect action.
3. Press Home and lock the phone.
4. Confirm the App does not immediately reacquire the connected-device foreground owner or reconnect against the user's explicit action.

Pass criteria:

- explicit user disconnect wins over automatic foreground ownership/reconnect.

## 7. Background BLE file download

1. Start downloading a device recording file.
2. Confirm notification text shows a truthful transfer state/progress where total bytes are known.
3. Press Home during transfer.
4. Lock the phone during transfer.
5. Unlock after enough time for the transfer to finish.
6. Return to Voica.
7. Confirm exactly one completed local recording is registered.
8. Play the downloaded recording.
9. Repeat with another file and briefly background/foreground multiple times.

Pass criteria:

- Home/lock does not cause the old `BACKGROUND` cancellation path;
- notification progress derives from real received/expected bytes;
- no duplicate final file or duplicate library registration;
- no `.part` file is treated as completed;
- completed file remains playable and valid.

## 8. Cancellation

1. Start a BLE file download.
2. Cancel from the App before completion.
3. Confirm the operation ends as cancelled and no completed asset is registered.
4. Start the same download again and confirm a clean new transfer can finish.

Pass criteria:

- cancellation does not produce false completion;
- retry does not create duplicate completed assets.

## 9. Audio Focus / route regression

While playback is active, test at least:

- another app temporarily taking Audio Focus;
- headphones/Bluetooth audio route change if available;
- unplug/noisy-output event if wired output is available.

Pass criteria:

- transient focus loss pauses and may resume correctly even while Voica is backgrounded;
- permanent focus loss does not auto-resume;
- noisy-output protection still pauses playback.

## 10. Evidence to report

For any failure, record:

- exact step;
- foreground/Home/locked state;
- recorder connection/recording state;
- operation shown in notification;
- whether audio was still playing;
- whether reconnect happened;
- screenshot/video if useful;
- whether reopening Voica recovered to the correct state.

If all sections pass, report **“B1 测试通过”**. Do not treat B1 as Stage 13B final acceptance; model backgrounding, process-death recovery and 30/60/120 minute stress validation remain later gates.
