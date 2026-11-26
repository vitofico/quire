# Reading statistics

**More → Reading statistics** in the library (also the chart button on the insights
screen) is the app's one statistics screen. It shows how long, when and how fast
someone reads, from sessions recorded on this device, and a **Your library** card
with the figures Quire's server counts. Settings has **Record reading statistics**,
on by default; turning it off stops new sessions and offers to clear the stored
ones. Statistics are kept on the device and in the phone's own Android backup;
they are never synchronised with Quire's server or sent to AI.

## Library figures

The **Your library** card comes from the server's `GET /library/v1/stats`: how many
books the library holds, how many are finished, being read and abandoned, the top
authors and the top themes from AI insights. The server counts every book in the
library and the progress synced from every device, so it needs no recorded sessions
and still shows with recording off. Its finished count therefore replaces the
device's own **Books finished** tile while it is there; when the server can't be
reached the card says why with a retry, and the tile counts the books finished on
this device instead, even before any session is recorded. Without a Quire server
set up (offline only, or a catalog-only account) the card is left out and nothing
is fetched. The last figures received are kept in memory for the life of the app,
with the account they belong to, and shown at once on reopening while fresh ones
load; another account never sees them, and signing out drops them.

## What a session is

The reader keeps a clock while the book is in the foreground. It starts on open
and on return to the app, and stops when the app goes to the background, the
screen turns off or the book closes. With no page turn or scroll for 3 minutes
the session ends a short grace after the last activity: the reader's median gap
between page turns in that session, at least 30 seconds. The next page turn
starts a new session. Sessions shorter than 30 seconds are dropped. Durations
come from a clock that only moves forward, so changing the device time cannot
stretch a session; the wall clock only dates its start.

Only forward page turns and scrolls made by the reader count as reading: a
position change within 5 seconds of a swipe, tap or scroll, of at most about
ten positions. Returning to a saved place, jumping to a highlight and anything
else that moves the book without a gesture are not credited, and neither is the
2 seconds after such a jump. Links are mostly not credited: a link is a tap
too, so one that lands within about ten positions of the page counts as
reading, while a longer jump only moves the baseline. Time spent still counts.

Recording is in memory while reading; the row is written once, when the session
ends, on a background scope. The reader never reads the table back.

## Speed

Speed is in words per minute. Each book's words are counted once per device on
a background thread when it is first opened, and cached by book. Words read in a
session is the share of the book read times its words, so it assumes an even
word density across the book. Chinese and Japanese characters count one word
each. A book's position can move in lumps (some books report a new one only at
each chapter), so a session may gain no words or a whole chapter; speed is
therefore the total over all sessions, and only sessions without a word count
are left out. A book whose total is above 1,500 words per minute was skimmed and
counts for time but not for speed.
A speed is shown only after 5 minutes and 500 words of backing. A book's time
left uses its own pace after 10 minutes of reading, otherwise the overall pace.

## Storage

Rows live in the local Room tables `reading_sessions` and `book_word_counts`
(database version 17). Sessions are keyed by the book's content hash and keep a
title and author snapshot, so removing a book from the library keeps its
history. Each row stores UTC instants and the device's UTC offset at the start,
so a session stays in the local hour and day it was read even after travel or
a clock change. **Clear statistics** on the screen or in Settings deletes every
session. The tables are part of the app's data in the phone's own Android
backup (the backup rules leave out only Calibre credentials), and are never
sent to Quire's server or to AI.
