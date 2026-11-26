# Notifications and quiet

**Settings → Notifications and quiet** holds three optional features. All three are off on a new
install, and while they stay off Quire schedules no background work, asks for no permission and
creates no notification channel.

- **New books in your catalogs.** About every 12 hours, on any network and with the battery not
  low, Quire opens each saved catalog's list of newest books and posts one notification per
  catalog that has new ones ("3 new books in Library", listing the titles). Tapping it opens that
  catalog. The first check after switching on only records what is there. The list is found by
  the OPDS `http://opds-spec.org/sort/new` link, or by a navigation entry ending in `/new`
  (Calibre-Web), `/recently-added` (Kavita) or `/navcatalog/4f6e6577657374` (the calibre content
  server's Newest, which has no sort/new link), or by one sorted with `sort_order=release_date`
  (Project Gutenberg's Latest); a catalog without one is skipped and named under the switch. Of
  the packaged open catalogs, Project Gutenberg's main catalog and textos.info have such a list;
  the Gutenberg language searches and Ebooks libres et gratuits do not. Quire remembers up
  to 300 entry ids per catalog in a preferences file of its own, never in the library database
  and never in backups (a restored phone records a fresh baseline instead of announcing every
  book since the backup), and forgets them when the switch goes off. The 12-hour job runs only
  while at least one catalog is saved. With notifications refused or the channel blocked a run
  ends without fetching; a server error or timeout retries an hour later.
- **Reading reminder.** A notification around a time you choose (21:00 at first), on the days
  you choose. It names the book last opened in the reader here and opens it at your place, and it
  stays away on a day a book was opened on this device. Only opening a book counts: marking one
  finished or abandoned does not, and a book since finished or abandoned is not named. It runs
  on WorkManager, not exact alarms, so with the phone idle (Doze, App Standby) it can come late;
  one more than three hours late is skipped. The setting says so. At start Quire books it again
  if the booked run no longer falls at the chosen local time (the phone changed time zone).
- **Quiet while reading.** While a book is open on screen, Quire turns on a Do Not Disturb rule
  of its own, priority only, so the calls and alarms you already let through still come. The rule
  appears by name ("Reading in Quire") in the system's Do Not Disturb settings. Leaving the book
  or the app turns the rule off. Because Quire only ever switches its own rule, Do Not Disturb you
  turned on by hand, or another rule such as Bedtime, stays exactly as it is, whether it started
  before or during reading. Switching the feature off deletes the rule. It needs Do Not Disturb
  access, which Android grants in its own settings screen, and Android 10 or later; on older or
  low-memory phones the row is hidden.

  If Quire stops mid-read, the rule is turned off: by the crash handler on a crash, by the next
  start, and by a check WorkManager runs about every half hour while a book is open, which
  restarts a dead process. What remains: after a force-stop Android cancels that check too, so
  the phone stays quiet until Quire is next opened, or until the reader turns "Reading in Quire"
  off in system settings.

On Android 13 and later the notification permission is asked when one of the two notifying
features is switched on. Android shows its prompt at most twice; after that the request comes
back refused at once, the switch stays off, and the section links to the app's notification
settings.

Notification taps open Quire with a `quire://catalog/<id>` or `quire://reminder` address and a
token only this install knows, so another app cannot use Quire's notifications to open a book.
