# Reading statistics

**More → Reading statistics** in the library (also the chart button on the insights
screen) shows how long, when and how fast someone reads. Settings has **Record
reading statistics**, on by default; turning it off stops new sessions and offers
to clear the stored ones. Nothing here is synchronised, uploaded or sent to AI.

## What a session is

The reader keeps a clock while the book is in the foreground. It starts on open
and on return to the app, and stops when the app goes to the background, the
screen turns off or the book closes. With no page turn or scroll for 3 minutes
the session ends at the last activity plus 3 minutes, and the next page turn
starts a new one. Sessions shorter than 30 seconds are dropped.

Only forward page turns and scrolls made by the reader count as reading: a
position change within 5 seconds of a swipe, tap or scroll, of at most about
ten positions. Returning to a saved place, jumping to a highlight, links and
anything else that moves the book without a gesture are not credited, and
neither is the 2 seconds after such a jump. Time spent still counts.

Recording is in memory while reading; the row is written once, when the session
ends, on a background scope. The reader never reads the table back.

## Speed

Speed is in words per minute. Each book's words are counted once per device on
a background thread when it is first opened, and cached by book. Words read in a
session is the share of the book read times its words, so it assumes an even
word density across the book. Chinese and Japanese characters count one word
each. A session above 1,500 words per minute counts for time but not for speed.
A speed is shown only after 5 minutes and 500 words of backing. A book's time
left uses its own pace after 10 minutes of reading, otherwise the overall pace.

## Storage

Rows live in the local Room tables `reading_sessions` and `book_word_counts`
(database version 17). Sessions are keyed by the book's content hash and keep a
title and author snapshot, so removing a book from the library keeps its
history. Each row stores UTC instants and the device's UTC offset at the start,
so a session stays in the local hour and day it was read even after travel or
a clock change. **Clear statistics** on the screen or in Settings deletes every
session. Existing Android backup rules apply.
