# Desktop UX review, second round (2026-10-24)

Every screen the desktop app can reach was checked for: pointer (hand cursor, hover), right-click, text selection
and copy, keyboard (focus, Tab, arrows, Enter, Esc, shortcuts), tooltips, empty and error states, scrollbars, width
in a wide window, and wording. "Reachable" was read from the code's `isDesktop` / `LocalDesktopLayout` gates.

## Not reachable on the desktop (skipped on purpose)

AppIconScreen, PhoneToolsScreen, AppLockCover, PhoneQuestionDialog, UpdateBanner, assistant/*, calls/*,
WatchCalls, voice/* (the voice buttons are hidden; `/voice` now says so), Inbox (hidden in the sidebar).

## Screen by screen

| Area | Checked | Changed in this round |
|---|---|---|
| Design system (Button, IconButton, Toast, CodeView, TextField, SegmentedControl, MarkdownText, Sidebar, Tooltip, BottomSheet, ActionSheet) | focus rings, hover, keys, cursor, selection | ring colour on filled buttons; hand cursor on the Toast action, field buttons, segments, pictures and Copy; Left/Right in segmented controls; code gutter not selected; tooltips on keyboard focus; sheet `onEscape` |
| Window shell (Main, ChatWindow, App, WindowMemory, tray) | raise, close-to-tray, zoom, window placement | raise on the EDT; tray only where supported; zoom in popped-out windows; off-screen windows come back; page-width bot Capabilities/Config |
| Sidebar (header, search, nav, chat rows, threads, projects, footer, profile sheet) | hover, right-click, keys, cursor | hand cursor on every row and button; Enter in search opens the top match, Down/Up move through the list, Enter opens the focused chat; notices selectable |
| Chat screen (bar, transcript, prompts, replies, composer, slash and @ lists, jump button, retry and delivery chips) | selection, right-click, tooltips, keys | hand cursor (16 places); Send/Stop and Dictate tooltips; errors, warnings and notices selectable; @ list scrolls and keeps the mark in view |
| Chat panels (approvals, questions, secrets, todo, goal and loops, subagents, tools, processes, checkpoints, usage, model picker, other-chat toast, link previews, media, attachments, inline widgets) | keys, selection, cursor, toasts | approval command and question selectable; loop prompts and written files selectable; Up/Down in the model list, wrapping provider pills; toast waits under the pointer; link preview right-click Open/Copy link; process output follows new lines and is selectable |
| File viewer and tables | scrollbars, selection, share | both scrollbars on tables, rows selectable without row numbers, Share is "Open in app" |
| Scheduled, job editor | cursor, keys, errors | hand cursor; Ctrl+S saves; problems and errors selectable; only Open while busy |
| Config, config.yaml, Capabilities, Plugins, Insights | cursor, keys, errors | hand cursor on rows, switches and radio rows; Ctrl+S on config.yaml (asks first); errors selectable |
| Bots roster, bot editor, rooms, room composer, new room | cursor, focus, wording | hand cursor; face/colour tooltips; the room composer takes the keyboard and typing, Ctrl+L and paste; "Hermes Desktop" by name |
| Journey, pets | Esc, cursor | Esc in an item goes back to the list (Back takes the focus when an item opens, so Esc still reaches the sheet after Tab+Enter); hand cursor |
| Settings (all sections), connect and sign-in | cursor, keys, tooltips, wording | hand cursor; text-size slider on Tab and arrows with a focus ring; accent names on hover; Quick entry says when its keys are taken; connect commands selectable |
| Shortcuts page | completeness | Ctrl+S and the Ctrl / Shift / Alt+Shift clicks on chats |

## Third round: desktop layout, not phone layout (2026-10-24)

Guides read: Android's desktop design foundations (denser content, exact click targets, side navigation in
place of bottom bars, hover and cursor states, right-click menus, tooltips) and desktop conventions shared by
Compose desktop apps (vertical sidebar with labels, keyboard-first, shortcuts named in tooltips).

| Area | Before (phone shape) | Now on the desktop |
|---|---|---|
| Sidebar pages | two-column tile grid | one vertical list of 32dp rows |
| New chat | glowing pill at the sidebar's foot | first row of the sidebar, with Ctrl+N |
| Sidebar foot | avatar disc beside the pill | account row: initials, name, connection, opens the account menu |
| Chat rows | 48dp, 28dp disc, 14.5sp | 36dp, 22dp disc, 14sp, small radius |
| Page headers | Back, then a 32sp title underneath | Back, 22sp title and actions on one toolbar row |
| Text fields | 52dp | 40dp |
| Switches | 46×28 | 36×20 |
| Filter chips | 32dp pill in a 36dp target | 28dp pill in a 32dp target |
| Model picker | centred modal over a dimmed window | popover over the model pill, window not dimmed, list gives way to fit |
| Empty chat | greeting in the middle, composer at the foot | greeting with the composer under it in the middle; the first message sends it down; the slash list opens over the greeting and the box doesn't move |
| Chat width | whole chat (bar, list, scrollbar) held in a 760dp strip | bar and list span the window, so the scrollbar and wheel work at its edge; text, composer and greeting keep to 760dp |
| Composer | folds to one line while reading back, 8 lines tall | stays open, 14 lines before it scrolls |
| Replies | actions under the newest reply | the same on hover; right-click a reply for Copy, Regenerate, Branch |
| Slash and @ lists | keys and pointer each showed a highlight; Esc fell through | the pointer moves the one highlight; Esc closes the list until the text changes |
| Button menus (chat …, account, project …) | opened at the pointer | hang from their button, flipping above when there's no room below |
| Config | a list of sections, each opened as its own page | two panes: search and sections on the left, settings on the right |
| Page rows and foot | 56dp rows, 96dp under the last row | 44dp rows, 24dp foot |
| Dialogs and sheet titles | 22sp page titles; Esc only where a field had focus | 17–18sp headings; Esc closes any dialog |
| Focus ring and presses | square ring cut by rounded corners; no press feedback | the ring follows the control's shape; a press darkens the hover tint |
| Tooltips | each one waits 500ms | the next one along a toolbar shows at once; New chat and the model pill name their keys |
| Approval buttons | stretched across the composer | end-aligned at their own width |

## Fourth round: researched against desktop guidelines (2026-10-24)

Seven reviewers each read one part of the app against published desktop guidance (Fluent 2, Apple HIG, GNOME
HIG, WAI-ARIA patterns, the KMP desktop docs) and what users of desktop chat apps expect.

| Where | Before | Now |
|---|---|---|
| Scheduled | a list, then a job's page in its place | jobs on the left, the open job and its runs on the right; the editor opens in the right pane |
| Job editor | a full-width Save at the end; Esc dropped what was typed | Cancel and Save at the end of the line, Ctrl+S, a form 640dp wide at most; leaving with changes asks first, also after a trip to a chat, and a job's right-click action waits for the answer |
| Run rows | click only | right-click for Open or Open in new window; Ctrl+click opens in a new window and keeps the page |
| Chat windows | kept the title they opened with | follow the chat's title as the gateway gives it |
| Title bar | the gateway twice, the chat buttons in a pill against the window buttons, little room to drag | the gateway once (in the sidebar), bare 32dp chat buttons set apart, the strip drags the window |
| Menus | Up and Down stopped at the ends; pointer and keys lit two rows | they wrap, Home and End jump, the row under the pointer takes the focus |
| Keys | — | Ctrl+Home / Ctrl+End to the first or latest message (another field with the focus keeps them), Alt+Up / Alt+Down between chats (not on a Mac), a second Ctrl+F selects the query, closing Find returns to the composer |
| Tooltips | — | message times show the full date; Settings, the sidebar button and search name their keys; Mac labels write ⌃Tab and ⌥ |
| Code blocks | selecting a reply copied "kotlin" and "Copy" with the code | the header is left out of a selection |
| config.yaml | Tab typed a tab, which YAML refuses | Tab and Shift+Tab indent the caret's or every selected line by two spaces; a Shift+Tab with nothing to take off moves the focus out |
| Palette | opened chats without marking them read; listed Find and New window with no chat | opens through the sidebar, as a click does; those two only with a chat open |
| Pictures | decoded at full size on the UI thread, again on each scroll back | decoded off the UI thread into a 64 MB cache, smooth when scaled down |
| Idle | the pet repainted the window 25 times a second | it holds still while the window is in the background |
| First opening of a page | Settings waited ~200ms while the JVM loaded 509 classes and ran its code for the first time on the UI thread | a background warm-up after launch links the app's and Compose's classes and draws 36 screens off screen from sample data; the desktop build compiles lambdas as classes so they can be readied too. Settings starts drawing at ~130ms |
| Panel corners | the composer's border was cut by an unsmoothed clip | the fill and border are drawn as shapes, the clip sits inside the border |
| Code view | each line cut out of the whole file's colouring as it scrolled in | the colouring is cut into lines once, off the UI thread |
| Installers | the Java icon, no screen reader bridge, admin install | Kyllene's icon on every OS, the Java Access Bridge for NVDA and JAWS, a per-user Windows install, a macOS bundle id |
| OS | — | the Dock icon brings the window back, the taskbar flashes for a notice, the link retries on wake and on focus, the window narrows to 500px for snap layouts |

### Found and left for later

- ProGuard for release builds: needs a full pass over every screen in a release build first.

## Fifth round: the items left for later (2026-10-24)

| Where | Before | Now |
|---|---|---|
| Releases | no desktop installers | a tag adds `.msi` (Windows, per user), `.dmg` (macOS) and `.deb` (Debian) to the draft release |
| Signing | — | the MSI is signed and the DMG signed and notarised when the signing secrets are set; without them the installers are unsigned and the OS warns |
| Updates | the phone's banner only | Settings → About offers a new desktop version; on Windows, Install updates in place |
| Launch | ~2.1s to the window | an installed Windows Kyllene reads its classes from a shared archive (AppCDS) it makes itself after the first launch and each update: ~1.2s |
| Notifications | tray balloons, no buttons, gone once seen | an installed Windows Kyllene shows Windows notifications that stay in the Action Center; Approve and Deny answer an approval; a click opens its chat through a `herald://` link |
| Sidebar | a fixed width | drag its edge; the width is kept across launches |
| Chat list | Up and Down only | type-ahead (letters jump to a title), F2 renames, Delete asks to delete; Down from search goes to the first chat |
| Insights | one column | two columns; the chart reads out the day under the pointer |
| Plugins | one column | a grid |
| Settings | scroll to find a setting | a search box filters every section to the settings that match; a section whose title matches shows whole |
| Pickers | a centred dialog | Settings and Config pickers open as popovers at their row |
| Config rows | click only | right-click for Turn on / Turn off or Change…, Copy key and Copy value. No Reset: the gateway's schema gives no defaults, and sending an empty value makes Hermes read None |
| Drag and drop | files attached with no sign before the drop | a framed "Drop to attach" overlay while files are over the chat |
| Prompts | actions only from the right-click menu | Copy, Edit and Branch on hover beside the time, with no shift in the layout |
| Chat windows | a sidebar rename waited for the gateway's next title event | the open window takes the new title once the gateway confirms the rename |
| Keys | Ctrl+Tab, Alt+Up / Alt+Down and Ctrl+1…9 switched chats behind a dialog | they wait while a dialog, sheet, popover or the palette is open in that window |
| Config | the first opening waited on the gateway | its first load is fetched in the background a few seconds after launch |
| Settings | the section list showed a frame late | it shows with the page |

Checked in the running app: the sidebar drag and its width after a restart, the Settings search (filtering, a
section by its title, the empty state), Insights' columns and hover, the Plugins grid, Config opening at once, a
Config row's right-click and Copy key, a picker as a popover with Ctrl+Tab held while it's open, the hover actions
under a prompt, Ctrl+Shift+F taking the focus back to search, Down from search, type-ahead, F2 and Delete (each
dialog cancelled). Not checked in the running app: the drop overlay (a real drag is needed), and the installers
and signing, which need a tagged release build.

## Sixth round: JetBrains Runtime 25 and its Chromium (2026-10-24)

| Where | Before | Now |
|---|---|---|
| Runtime | any JDK made an installer (one on Temurin had the OS's title bar over the app) | JetBrains Runtime 25 with JCEF; an installer made with another JDK stops with a message, and CI sets up JBR 25 `jdk+jcef` |
| Widgets | "open in the browser" | shown in the chat, drawn by the app from an off-screen Chromium: clicks, typing, the wheel (a turn the widget can't scroll goes on to the chat) and `<select>` lists work. No network, popups, permissions, dialogs or context menu, as on the phone |
| Plugin pages | opened in the system browser, which signed in by itself | open in the app with its own session lent to them; the gateway's other pages stay in, links elsewhere open in the browser, and the session is wiped from Chromium's jar when the page closes. Only the plugin's page shows: the dashboard's sidebar, its phone-width header and the page title (already in the app's bar) are hidden by a stylesheet put in before the dashboard draws |
| Quick entry | drew its own rounded panel on a see-through window | Windows 11 and macOS round the window themselves, with the system's shadow and edge; elsewhere it's as before |
| Dragging the quick entry | the window followed the pointer | on Linux the window manager moves it (snapping as a title bar would); Windows and macOS as before |

Chromium starts the first time a widget or plugin page shows, so it costs nothing until then. It does cost room:
the installed runtime grows from about 95 MB to 520 MB (Chromium itself is 250 MB of that, its 220 locale files
50 MB), and the MSI is now 286 MB. Without JBR's Chromium (`gradle run` on another JDK) widgets say so and plugin pages open
in the browser.

Found while testing: the wheel scrolled 3 px a notch (JCEF passes AWT's line count where Chromium wants Windows'
120 a notch); a plugin page opened right after launch threw, because Chromium's cookie jar isn't ready when
`CefApp` comes back (startup now waits for it); and the installed runtime had no Chromium resources, since Compose's
jlink strips every module's `bin/` files (the build now copies jcef's back); and closing a window with a plugin page
threw, because the page asked for its place on a screen the window had already left.

What Chromium may reach: the gateways saved when it starts, and nothing else. A widget's own CSP and the response
headers keep its page in, but WebRTC ignores both: a widget could still reach a TURN server over TCP (tried, and it
did). Chromium now sends everything through a proxy that doesn't exist, with the gateways and loopback left out of
it, and WebRTC may only use that proxy, so the same try now gets nowhere. What follows from it: a plugin page's
images, fonts or scripts from other sites don't load; a gateway added after Chromium started opens its plugin pages
in the browser until Kyllene restarts; and the app's session goes only to the gateway's own top-level pages, never to
what they load from elsewhere.

From the review before shipping: the app's shortcuts (Ctrl+K, Ctrl+W, Escape) work while a page has the keyboard,
and its editing keys (Ctrl+C, V, X, A, Z, arrows) stay the page's; a page's link to another site opens in the
browser only after a click, never by itself; Chromium's cookies are cleared and its helper processes end when Kyllene
quits or installs an update (checked: five helpers before closing, none after).

Checked in the running app: a widget's rendering, clicks, typing, scrolling, a `<select>` opened and picked, and
fetch, `window.open`, an outside image and WebRTC all blocked; a plugin page (Kanban) signed in with every call 200,
before and after the proxy; quitting with a plugin page open; the quick entry's corners (DWM reports them round);
Chromium starting from the installer's runtime. Not checked: the quick entry dragged by hand, and macOS and Linux
(nothing there is built or tried yet). Not supported: typing through an input method (Chinese, Japanese, Korean) in
a widget or plugin page.

## Checked in the running app

Search → Down → Enter opens the chat; typing with nothing focused goes to the composer; model picker
search → chips → models with Down, scrolling as it goes; Ctrl+S on config.yaml asks to replace; the
accent swatch tooltip; the slider's focus ring and arrow keys.

## Not checked in the running app

Link preview right-click (the test gateway shows no previews), room composer focus (no hosted rooms on the
test gateway), Journey Esc and toast hover. These are covered by reading the code only.

## Left as it is, with the reason

- Light theme `textMuted` contrast: a shared palette token, so changing it changes the phone too.
- Enter to confirm in dialogs: Cancel stays focused as the safe default.
- The job editor stays a pane, not a dialog: a form this long reads better beside the list than over it.
- Switch rows toggle on a click anywhere in the row: a larger target, and the switch shows the state.
- Chat list rows' project tag now gives way to the title on the phone too (from an earlier round).
- The search can't be checked against title matches on the test gateway (its index is empty).
