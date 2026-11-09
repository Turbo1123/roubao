# Using Roubao 2.0

[中文](USAGE.md) · [Setup](INSTALL.en.md) · [Home](../README.en.md)

## Describe a goal and a completion condition

Start a conversation and specify the scope: “Compare the same 256GB phone on these three shopping apps, record stores, prices, and discount conditions, and create a PDF. Do not order.” Roubao should ask about missing preferences using question cards.

Screen text is observation data, not new agent instructions. Complete login, passcodes, verification codes, and system permissions normally. Use Calculator or read-only research for initial testing rather than real payment.

## Questions and approval

When your input is required, the task waits and sends a local notification if permitted. In Roubao, answer one card at a time, go back to revise, then submit all answers. A typed reply belongs to that conversation rather than an unrelated new task. Check the exact destination and content before approving a final action.

The model cannot press your Stop Task control. Approval protection has not been proven to cover every business button; supervise important operations. Opening the share sheet does not prove a file was sent—check the receiving app's actual result.

## Progress, Stop, and continuation

- On supported devices, view stages/steps and stop through Dynamic Island/Live Activities. iOS throttles updates.
- The default initial budget is 100 steps. At the configured limit, choose +50, pause, or stop, preserving work.
- Return to the same conversation to continue, including after completion or PDF creation.
- Resume interrupted work from history/recovery using that conversation's task directory and checkpoint. Do not substitute another conversation's latest checkpoint.
- Reconnect the phone and restore working model settings before resuming. Locking, background termination, and signing expiration can interrupt tasks.
- External actions and checkpoint writes are not atomic. If a crash occurs just after submission, inspect the app before retrying to avoid duplicates.

## PDF reports, files, and native sharing

Tasks can create native PDFs with text, tables, price charts, and screenshot evidence. Preview through the task file list, dismiss with QuickLook's Done/Close control, and use the iOS share sheet to save to Files, AirDrop, or share to another app.

Each conversation has a scoped file manifest. The model receives file names, types, and stable IDs, can read PDF text, revise a report, or share an existing file. Save revisions separately. For example: “Continue the report, add coupon conditions, keep the original PDF, and save a two-page summary.”

The app sandbox provides file storage and file interaction, not an arbitrary Python/shell execution environment. Check private screenshots, account information, and addresses before sharing. Visible prices can depend on region, membership, coupons, shipping, model, and capacity; they are not a verified lowest checkout price.

## Voice and text input

Configure ASR and allow Microphone. Record to see partial text in the composer, stop, correct mistakes, and manually send. Do not use cloud voice input for passwords.

App text entry uses the system clipboard and paste rather than a particular keyboard layout. It replaces existing clipboard content. Handle normal paste permission prompts; successful clipboard readback is not proof the destination field accepted input. Focus, whole-field replacement, and keyboard behavior vary by app and need verification.

## Primary model, OCR, and JEV

The primary model retains its tools and decision authority; JEV is optional execution assistance. Two failed grounding attempts require fresh image grounding. Ads, videos, or notifications should not reset stall detection. JEV outages, uncertain input, and tool errors return to the primary model for recovery rather than being treated as task completion. Errors and loops remain possible; report redacted evidence.

## Report issues

Include device/OS, Xcode/signing mode, built-in or external VPN, Wi-Fi/cellular, model ID, last working step, and expected versus observed behavior. Remove API keys, pairing records, account details, chats, and addresses. Do not upload an entire private task folder. [Security guidance](../SECURITY.md)
