# Notifications and quiet

**Settings → Notifications and quiet** holds three optional features. All three are off on a new
install, and while they stay off Quire schedules no background work, asks for no permission and
creates no notification channel.

- **New books in your catalogs.** About every 12 hours, on any network and with the battery not
  low, Quire opens each saved catalog's list of newest books and posts one notification per
  catalog that has new ones ("3 new books in Library: Dune and 2 more"). Tapping it opens that
  catalog. The first check after switching on only records what is there. The list is found by
  the OPDS `http://opds-spec.org/sort/new` link, or by a navigation entry ending in `/new`
  (Calibre-Web, calibre) or `/recently-added` (Kavita); a catalog without one is skipped and named
  under the switch. Quire remembers up to 300 entry ids per catalog in its preferences, never in
  the library database, and forgets them when the switch goes off.
- **Reading reminder.** A daily notification at a time you choose (21:00 at first), on the days
  you choose. It names the book you are reading and opens it at your place, and it stays away on
  a day you have already read, on this device or a synced one.
- **Quiet while reading.** While a book is open on screen the phone goes to Do Not Disturb,
  priority only, so the calls and alarms you already let through still come. Leaving the book or
  the app puts it back. If Do Not Disturb was already on, Quire leaves it alone; if you change it
  while reading, your choice stands. If Quire is closed by the system mid-read, the next start puts
  it back. This needs Do Not Disturb access, which Android grants in its own settings screen.

On Android 13 and later the notification permission is asked the first time one of the two
notifying features is switched on; if it is refused, the switch stays off and the section says so.
