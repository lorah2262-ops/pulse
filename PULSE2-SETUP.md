# Pulse 2 setup

## 1. Install
1. Install the latest **Pulse.apk** from the repo's Releases page (built automatically by GitHub on every change).
2. Upload `Pulse.min.js` (also on the Releases page) to the watch with Ollee Forge. It replaces the old Pulse face (needed for the slower answer scroll).
3. In the Pulse app, go through SETUP. The new row is **Calendar** (for nEXt).

## 2. Buttons
| Screen | Tap A | Tap L | Hold A | Hold L |
|---|---|---|---|---|
| Music | play / pause | next (double tap = previous) | menu | volume |
| Menu | open | next item | back | back |
| Volume | up | down | mute | back |
| Typing | next letter | jump A F K P U 0 space | results | delete / back |
| Results | play | next | — | back to typing |
| Macros | run | next | — | back |
| Ask AI: listening | done talking | — | — | cancel |
| Ask AI: the answer | next row | previous row | ask again (voice) | music |
| Menu → AnSWEr | reopens the last answer where you left it | | | |
| nEXt (calendar) | back to the list | next item (up to 5) | — | music |
| Inbox | next message | replay | back | clear all |

## 3. MacroDroid (one macro per slot)
- **Trigger:** Intent Received
  - Action: `com.ollee.pulse.MACRO`
  - Extra `slot` = the slot number (1 to 8)
- **Actions:** whatever the slot does
- **Optional reply to the watch:** Send Intent
  - Target: Broadcast
  - Action: `com.ollee.pulse.SHOW`
  - Extra `text` = what to show, e.g. `[battery]%`

Without a reply within 6 s the watch shows `nO AnS`.
A SHOW sent at any other time shows up like a notification tagged MACrO.

StAtUS and nEXt are built into Pulse, so they need no macro.

## 4. bAL (TM data balance)
Two macros:
1. **Pulse 8 bAL** (trigger: slot 8): Send Intent `text` = `ASKInG`, then Send SMS `DATABAL` to `8080`.
2. **Pulse 8 bAL reply** (trigger: SMS Received from `8080`, any content):
   - Text Manipulation → Extract text from `{sms_message}`, regex `[\d.]+\s?[GM]B`, first match, into global `bal`
   - Send Intent `com.ollee.pulse.SHOW`, `text` = `{v=bal}`

A reply that arrives within 2 minutes of running the macro pops up on the watch by itself.

## 5. Ask AI by voice
1. Get a free Gemini key at aistudio.google.com/apikey and paste it in the app's **GEMINI KEY** section.
2. Allow **Microphone** in Setup.
3. On the watch: hold A → **ASK AI** → tap A, then speak after the beep (in your earbuds if they're connected).
Pulse records you itself and waits through pauses; it stops after about 2 seconds of quiet, or tap A when you're done. Soft voice? Set **Mic sensitivity** in the app: **High** (default) suits most; **Max** hears a whisper but may also pick up a noisy room. Gemini hears the recording directly: row 1 shows **Q** and the words it heard, then the answer. If it couldn't make you out you'll see `Q ?` and `SAY AGAIN`: hold A to ask again.

**If it mishears you:** in the app, **Last question** shows what Gemini heard and **Play** lets you hear the exact recording Pulse sent. If the recording sounds cut off or too quiet, change **Mic sensitivity**. If it sounds clear but Gemini still gets it wrong, switch **Listen with** to **Phone** (Google speech-to-text, the earlier way; it also waits through pauses).

The answer comes as short rows (up to 12; tables one row per line) that you step through with A, with the row number on the small digits. Each row scrolls round until you move on; set **Answer scroll** in the app (Normal / Slow / Slower). Switched to another watch face while reading? Coming back to Pulse within 10 minutes opens the answer again by itself. Went back to music? Hold A → **AnSWEr** (right after ASK AI in the menu) reopens the last answer at the row you were on; it's kept even if the app restarts. Answers stream in row by row (row 1 shows about a second after asking) from Flash with minimal thinking; if Flash is at its limit or busy, Flash-Lite answers instead. Turn on **Deeper thinking** in the app for hard questions (slower).

## 6. Ask with a photo
For when speaking doesn't work. In the app, open **ASK WITH A PHOTO**:
1. Type a question, or leave it empty (Gemini then reads and answers whatever is in the photo: a quiz, a math problem, a label, a sign).
2. Tap **Camera** to take a picture, or **Gallery** to pick one.
3. The answer appears on the watch (row 1 `Q …`, then the rows, same buttons as Ask AI) and in the app under the picture.

The watch stays connected while the camera or gallery is open: the connection runs in Pulse's background service, not on the app's screen. If it had dropped, asking reconnects it first.

**Answer with: Gemini app** (no key, no limits, no timeouts). Set the switch in the photo card, then take or pick the photo: it opens in the Gemini app with your question filled in. Tap send there; when it answers, tap **Share** on the answer (or hold it, then Share) and pick **Pulse watch**. The answer goes to the watch as rows and is kept under **AnSWEr**.

## 7. Share anything to the watch
From any app (the Gemini app, ChatGPT, Google Lens, a web page, notes), select or share text → **Pulse watch**. It shows on the watch like an AI answer: A next row, L previous, hold L music, and it's kept under **AnSWEr**. Markdown is cleaned up, numbered answers stay numbered (`16 IgG`), and long lines are split into rows (up to 80).

## 7. Battery saver (stage 1)
Upload the new `Pulse.min.js` together with the app: saver mode, the clock without seconds and the hourly battery report live in the face.

In the app:
- **BATTERY SAVER → Quiet off-screen** (on): while another watch face is showing, song changes stay on the phone; the latest is sent when the Pulse face opens.
- **Batch messages** (off / 2 / 5 / 10 min): messages are held and sent together. One wake-up for the watch instead of one per message. Calls are never held.
- **Watch saver mode** (off / below 2.80, 2.85, 2.90 V / always): half frame rate, no beeps (calls still beep 4 times), still bars, the title scrolls once per song.
- **Seconds on the idle clock** (off): the clock redraws once a minute.
- **Night link-off** (off) with **From, to** hours: Bluetooth to the watch is off in those hours. Tap Connect to bring it back early.
- **APPS → Only chosen apps** (off): when on, only the apps you switch on reach the watch. Apps appear in the list after their first notification.
- **DRAIN METER**: messages sent (music, keep-alives, other), notifications and animated minutes, for this hour and the last 24.

In the face: the battery voltage is reported once an hour instead of every 10 minutes.

## 8. Watch features (stage 2)
Upload the new `Pulse.min.js` with the app: the frame player, the timer and the tempo live in the face.

- **Trigger** (MACROS, off by default): **Hold A + L runs** and **Double-tap A runs** each open a list of your macros; pick one (or Off). The combo then runs that macro from the music screen without opening the menu. While Double-tap A is set, play/pause waits a third of a second.
- **Recorder timer**: in MACROS, tap a slot's type until it says **Recorder**. Running it sends the macro as usual with an extra `state` = `start`, and the watch counts the time (HR mark lit). Tap A stops (the macro runs again with `state` = `stop`) and shows the total; tap L goes back to music while it keeps recording. Hold A on the slot in the MACrO list brings the timer back. If your macro is a simple toggle it needs no change.
- **Pulse as a platform**: a macro can ask a question on the watch.
  - Ask: Send Intent (broadcast) `com.ollee.pulse.ASK`, extras `choices` = `YES|NO|LAtEr`, optional `title` and `id`.
  - On the watch: tap L next choice, tap A pick, hold to cancel. 30 s without a press cancels.
  - Answer: trigger Intent Received `com.ollee.pulse.PICK`, extras `id`, `index` (1, 2, 3... or 0 if cancelled) and `choice`.
  - Asked while another face is showing: the watch beeps and asks when you open Pulse (within 2 minutes).
- **Frame player** (ANIMATIONS): six built-in clips with Play buttons, your own (one frame per line, 6 characters), and **Before a message** to play a clip ahead of each popup. From a macro: `com.ollee.pulse.PLAY` with extra `name` (a clip's name) or `frames` (`f1|f2|...`), optional `speed` and `loops`.
- **Real beat** (FEATURES, on): for each new song the phone listens to its own output for about 8 s and sends the tempo once; the bars and colon then move at that tempo. Needs the Microphone permission. Skipped in watch saver mode.
- **Turn-by-turn** (FEATURES, on): while Google Maps navigates, the Pulse face shows the next turn and distance: `L  200` with `M` on the small digits (L left, r right, UP straight, Ut U-turn, rb roundabout, En arriving, GO unknown). Any button goes back to music until the next turn. To spare the watch battery the screen is still (the watch stays at one frame a second) and only a new turn and a few distance marks are sent: each whole km, then 500, 200, 100 and 50 m. The app's status card shows a **Maps** line with what Pulse read from the notification.

## 9. The new look
The app is now light: a soft blue background, frosted white cards and one blue accent, with five tabs at the bottom.

- **Home**: connection status with Connect, Test and Disconnect; now playing; four shortcuts (Saver mode, Messages, Trigger, Drain) that open their tab; Setup.
- **Watch**: Features, Animations, YouTube key, Gemini key, Ask with a photo, and the button guide.
- **Messages**: which apps reach the watch.
- **Macros**: the macro slots and the two triggers.
- **Battery**: the watch battery, Battery saver, and the Drain meter.

Back returns to Home first. The watch face is unchanged by this build.

## 10. Motion
Everything that moves uses one spring (light, stiff, little damping), so it overshoots and wobbles two or three times before it rests.

- **Presses**: buttons, chips and cards squash while held and spring back when let go.
- **Switches**: the knob stretches as it travels and squashes against the end it hits.
- **Tabs**: the blue capsule slides under the chosen tab, stretching wide on the way; the page springs in from its side.
- **Cards**: opening one unrolls it from its top edge, and the cards below are pushed away and settle.
- **Scrolling**: a page can be pulled past its ends like a rubber band and springs back.
- **Values**: a chip that changes pops.

Also in this build: card titles in normal case, shorter hints, the Drain meter as a table, the MacroDroid intents in their own card (tap a name to copy it), and fades that keep cards clear of the clock and the tab bar.

## 11. App features (stage 3)

Load the `Pulse.min.js` from this zip in Forge first: it now tells the app its version (v3).

- **Live mirror** (Watch): the last screen the phone put on the watch, in a small LCD. Long text scrolls.
- **Profiles** (Home): School, Home, Sleep. Tap one to set the feature switches and battery savers. Hold one to save the switches as they are now into it. Each can start by itself at an hour.
- **Rules** (Messages): mute group chats unless your name is in the message, never show messages with certain words, and a rule per app (tap the chip next to an app's switch): everything, only with my name, only with these words, muted.
- **Watch menu** (Watch): rename, move up or hide the items of the hold-A menu.
- **Forecast** (Battery): days left at the rate the voltage is falling, down to the voltage you count as empty, and a reminder notification to buy a cell.
- **Connection timeline** (Watch): every change of the link, with the reason for each drop.
- **Sent to the watch** (Watch): every line pushed, newest first. "(face off)" = sent while another face was showing.
- **Version handshake** (Home): a warning in the status card when the face on the watch is not the one this app expects.
- **Setup file** (Home): Export writes one file with your setup (no Gemini or YouTube keys); Import puts one in place.
- **Quick Settings tiles**: edit the phone's pull-down panel and add "Pulse watch" (connect / disconnect) and "Watch saver" (saver mode on now / back to your setting).
- **Gemini usage** (Watch): requests sent today against the daily limit you set. Google counts a day in Pacific time. A question can use two requests when Flash is busy and Flash-Lite answers instead.

## 12. Last rework

- **Watch menu**: hold an item and drag it to a new place; tap its name (shown as the watch shows it) to rename.
- **Live mirror**: laid out like a Casio F-91W screen: weekday and date small along the top, hours and minutes big with the colon, seconds smaller at the bottom right. Pulse's two day characters sit in the date's place.
- **Watch tab**: split into Watch face, AI & keys, and History by the switch under the title. It remembers the last one.

## 13. More motion

- **Live mirror**: shaped like the Forge emulator's watch (L and M on the left, A on the right), in frosted glass with the Pulse blue line.
- **Connect button**: tap it and a phone and a watch roll in from its ends; dots run between them while linking; linked, they huddle together and the button says Disconnect.
- **Numbers roll**: the battery voltage and the Gemini count roll to new values like an odometer. Gemini usage is a ring that fills.
- **Menus**: press a chip, slide down the menu and lift on your choice; a highlight glides under your finger.
- **History**: tap any line of the Connection timeline or Sent to the watch: it grows into a card with the details (for a drop: what the code means and how long until it came back).
- **Run from here** (Macros): tap the round button and your macros fan out in a spiral; tap one to run it. MacroDroid macros run even with the watch on another face; StAtUS, nEXt and rEC need the Pulse face open.

## 14. Last motion pass

- **Fast only when needed**: the link stays in Bluetooth's low-power mode, switches to the fast mode while a long send runs (80+ bytes waiting: AI answers, search results, animation frames) and goes back to low power 1.5 s after the queue empties.
- **Hold the tab bar**: it turns to liquid, gathers into a drop under your finger and blooms into a glass menu: connect or disconnect, send a test, watch saver on now, and the three profiles.
- **Test**: the button stretches with a tilt into a card showing the test, folds into a blue pill that says it reached the watch (amber if the watch isn't linked) and slips back into the button.
- **Drain meter**: List or Chart. Switching, the rows shrink into their icons, which float loose and settle on top of bars that grow up under them; back to List runs it in reverse.
- **Connect button**: a "+" breathes between the phone and the watch while it links.

## 15. Polish

- **Test**: a compact card that stays on screen, drawn by itself so it moves smoothly (stretch with a tilt, card, coloured pill, back into the button).
- **Drain chart**: rows shrink into their chips from the bottom up, the chips swirl around the card with a slight smear, land at their heights, then the bars rise under them and nudge them up. Reverse for the list.
- **Connect**: the word swells and melts, the phone and watch pop up in the middle with a "+" between; linked, the watch slides over beside the phone and "Disconnect" glides in from the right.

## 16. Presses and the chart, again

- **Presses**: the glow now sits under the words (they stay sharp), a pressed button rises above its neighbours instead of being cut by them, and the page holds still while your finger is on a button; drag clearly up or down and it lets go so you can scroll.
- **Drain chart**: no swirl. Like the reference: rows shrink into their icons from the bottom up, the icons drift loose on smooth curves to their spots, then the bars rise beneath them and give them a small push.
