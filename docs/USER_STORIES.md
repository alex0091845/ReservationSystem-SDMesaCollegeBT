# User Stories

Requirements for the BT building room reservation system at San Diego Mesa College, written as
user stories. Based on the initial stakeholder meeting notes, then updated with the team's decisions
from GitHub issues and pull requests (last updated October 27, 2026).

**Format:** each story has a priority and acceptance criteria (what "done" means). Notes under a
story record what the team has decided or built so far. Numbers like [#68] link to the GitHub issue or
pull request where it was discussed.

| Priority | Meaning |
|----------|---------|
| **Must** | Needed for the first release |
| **Should** | Important, but the first release can work without it |
| **Could** | Nice to have if time allows |
| **Later** | Not in current scope; kept so the design leaves room for it |

## Users

| Role | Who | Can do |
|------|-----|--------|
| **Visitor** | Anyone, not logged in (students, staff, public) | View the calendar and public events |
| **Faculty** | Faculty with an account set up by an admin | Everything a visitor can, plus book, edit, and cancel their own reservations |
| **Admin** | Room/system managers | Everything faculty can, plus manage users, permissions, bookable dates, and any reservation |

Students cannot book for now, because an employee must be present at all times. The design should
leave room for that to change (see [Later](#later)).

## Booking rules

These apply to every reservation story below.

- The room can only be reserved **8:00 AM – 5:00 PM**. An event that fills that whole range is shown as
  **"All day"** [#79].
- Reservations are only allowed **on dates an admin has marked as bookable**, and no later than the
  **last bookable date** (see AD3). The room is closed on holidays.
- New reservations must **start after the current time** (Pacific time). Editing a past reservation is
  still allowed so mistakes can be fixed [#34].
- Bookings may not overlap. **First come, first served**; the server rejects overlapping bookings, even
  when two people submit at the same moment [#79]. An admin can edit or cancel any booking to settle a
  conflict.
- The organizer is responsible for making sure participants are invited / allowed to attend. The
  system does not send invitations or control who can see a private event (see V2).

---

## Viewing the calendar (Visitor)

### V1. View the calendar — Must
As a visitor, I want to see when the room is booked, so that I know what's happening and when it's free.

- [ ] The calendar is viewable without logging in.
- [ ] I can zoom between **Year, Month, Week, and Day** views, and go back with a Back button, the
  breadcrumb, or the Esc key. A Today button jumps to the current date.
- [ ] On a phone, the calendar opens on the Day view.
- [ ] Booked times, closed days, and hours outside 8 AM – 5 PM are clearly shown as unavailable.
- [ ] The red "current time" line stays visible on top of events.

> **Built:** the zoomable calendar (Year → Month → Week → Day) [#63]. The Year view shades each day by
> how busy it is. Dates an admin blocks show on the calendar [#79, in review].

### V2. See event details — Must
As a visitor, I want to open an event and see its details, so that I know whether it's relevant to me.

- [ ] Public events show title, type, time, and organizer.
- [ ] Private events still appear on the calendar (so nobody thinks the room is free), but are labeled
  **"Private event"** with no private details.
- [ ] The organizer can choose to show the event's **name only** for a private event. Everything else
  stays hidden.

> **Decided** [#68]: private events show as "Private event" by default; the organizer may reveal just the
> name. The system will **not** handle invitations or give invited people access to the details. That
> is too much work for a feature that will rarely be used, and the organizer can email attendees
> directly. Can be revisited if users ask for it (see L6).
>
> **Built:** private event details are hidden from people who aren't allowed to see them [#44].

### V3. Tell event types apart — Must
As a visitor, I want event types (office hours, workshop, study group, class, department, etc.) to look
different, so that I can scan the calendar quickly.

- [ ] Each event type has its own color.
- [ ] Color is never the only signal — the type name or a label/icon is also shown (see A1).
- [ ] A legend explains the colors.
- [ ] Colors in the Week and Day views are soft enough to be easy on the eyes.

> **Discussion** [#69]: color-coding is standard (Apple, Teams, and Google calendars all use it), so we
> keep it. Still open: whether the extra label/icon makes it too cluttered — needs user feedback.
> Ideas for later: lighter event colors, letting organizers add an emoji or icon, or event banners
> (Could).

### V4. "Happening right now" — Must
As a visitor, I want to see what's going on in the room right now, so that I know if I can walk in.

- [ ] The landing page shows the current event (if any) and the next upcoming one(s).
- [ ] Private events show as "Private event", like in V2.
- [ ] Dates an admin has blocked are **not** listed as upcoming events and can't be checked in to [#79].

### V5. Landing page — Must
As a visitor, I want a clear landing page, so that I immediately see the most useful information.

- [ ] Shows today's schedule / "Happening right now".
- [ ] Shows the next upcoming event(s).
- [ ] Shows a check-in area for attendees, **above** "Upcoming Events".
- [ ] Shows the zoomable calendar (V1). No separate small month calendar.
- [ ] No "Create Reservation" button for visitors. Logged-in users create reservations from the
  dashboard [#77].
- [ ] Final design, content, and arrangement to be confirmed with Monica/Allan (see [Open questions](#open-questions)).

> **Built** [#74]: removed the small month calendar (the zoom calendar's Month view replaces it), and moved
> Check-In above Upcoming Events at every screen width.
>
> **Still proposed** [#70]:
> - Rename "Check-in" to **"Happening Now"**, with a "Check in" button on the event card that opens the
>   check-in form in a pop-up.
> - Put "Happening Now" at the top of the page (the first thing people see), then Upcoming Events, then
>   the calendar. Feedback so far: good idea for phones, but keep the calendar where it is on desktop.
> - Spell out "BT-216" (e.g. "Business and Technology – Room 216"), since people new to Mesa may not
>   know what it means. A map of where the BT building is was also suggested [#73] (see V8).

### V6. QR code to the calendar — Should
As a visitor walking past the room, I want to scan a QR code posted at the door, so that I can open the
calendar on my phone.

- [ ] A printable QR code links to the calendar page.
- [ ] A "QR Code" button in the top bar shows it.

> **In progress** [#57], [#78]: the button and an empty pop-up are done; the QR code itself is next.

### V7. Calendar on other pages — Could
As a department, I want to show the room calendar on our own web page, so that people don't have to go to
a separate site.

- [ ] The calendar can be embedded or linked from other pages (exact need to be clarified).

### V8. Find the room — Could
As a visitor new to campus, I want to see where the BT building and the room are, so that I can find it.

- [ ] The site explains what "BT-216" means and shows where it is (e.g. a map or a link to the campus map) [#73].

---

## Accounts (Faculty)

### F1. First login and accepting the account — Must
As a faculty member, I want to activate the account an admin made for me, so that I can start booking.

- [ ] I receive a notice that my account was set up, with a temporary password.
- [ ] On first login I must accept the room rules / terms ("I agree to abide by these rules").
- [ ] On first login I must change the temporary password.

### F2. Log in and out — Must
As a faculty member, I want to log in with my email and password, so that I can manage my reservations.

- [ ] Login uses email + password.
- [ ] The login button and login page are labeled **"Faculty/Admin Login"**, so students and visitors
  know it isn't for them [#53].
- [ ] A successful login shows a **green** message; a failed one shows a **red** message. Both meet
  contrast requirements [#49].
- [ ] The login page has a Back button to return to the calendar [#51].
- [ ] I can log out from any page, using a button labeled **"Log Out"**.
- [ ] Only logged-in faculty and admins see booking controls.

> **Built:** Back button [#55]. Labels, message colors, and "Log Out" are in review [#76].

---

## Reservations (Faculty)

### R1. Book a reservation — Must
As a faculty member, I want to reserve the room for a time slot, so that I can hold my event.

- [ ] I enter: event title, event type, date, start/end time, public or private, and an optional
  description. Expected participant count is **not** required.
- [ ] I can mark it as "Reserved for class" or "Reserved for department".
- [ ] I can pick start and end times precisely, not only in whole-hour blocks [#54].
- [ ] I can't pick times outside the [booking rules](#booking-rules). Times that have already passed
  are greyed out in the time picker [#34].
- [ ] If the slot is already taken, I'm told so before submitting, not after.
- [ ] I see a confirmation when the booking succeeds.
- [ ] The booking form works well on a phone.

> **Built:** more precise time selection and a better phone layout for the form [#64]; no booking in
> the past [#34]; the server blocks overlapping bookings [#79, in review].

### R2. Public vs. private events — Must
As a faculty member, I want to choose whether my event is public or private, so that I control who sees
the details.

- [ ] Public: details visible to all visitors.
- [ ] Private: shown to visitors only as "Private event" (see V2).
- [ ] Private: I can choose to show the event name only (see V2).

### R3. Recurring reservations — Must
As a faculty member, I want to book a repeating event (e.g. weekly office hours), so that I don't have to
book each week by hand.

- [ ] I can choose which days it repeats and when it ends.
- [ ] Repeats can't go past the last bookable date or land on blocked / closed days.
- [ ] If some dates conflict, I'm shown which ones before booking, and can book the rest.
- [ ] I can edit or cancel one occurrence or the whole series.
- [ ] If saving a series fails partway, nothing is left half-saved.
- [ ] Cancelling a whole series is done in one request, and checks that I'm allowed to cancel every
  occurrence [#52].

> **In review** [#56]: bulk delete for a series. Still to decide: whether check-in records for the
> deleted events are removed or kept (they could be useful for usage stats later — see C1 and L5).

### R4. View my reservations — Must
As a faculty member, I want a list of my upcoming reservations, so that I can keep track of them.

- [ ] Shows only my reservations, soonest first.
- [ ] A long list scrolls inside its box instead of pushing the rest of the page down [#74].

### R5. Edit my reservation — Must
As a faculty member, I want to change my reservation's details or time, so that I can fix mistakes or
adjust plans.

- [ ] I can edit only my own reservations.
- [ ] Changes follow the same rules and conflict checks as a new booking.
- [ ] I can still fix details (title, host, etc.) on a reservation that has already happened [#34].

### R6. Cancel my reservation — Must
As a faculty member, I want to cancel my reservation, so that the room is freed for others.

- [ ] I can cancel only my own reservations.
- [ ] I'm asked to confirm before it's cancelled.
- [ ] The time slot becomes available right away.
- [ ] If the cancel fails, I'm told so clearly [#33].
- [ ] Follows the cancel vs. delete rules in C1 (proposed): past or in-progress events can't be removed.

### R7. Email confirmation — Should
As a faculty member, I want an email when I book, change, or cancel, so that I have a record.

- [ ] Sent only to the event organizer.

### R8. Email reminder — Should
As a faculty member, I want a reminder before my event, so that I don't forget it.

- [ ] Sent to the organizer only; timing to be decided.

### R9. Save a draft — Could
As a faculty member, I want my half-filled booking form to be saved, so that I don't lose it if I leave the page.

---

## Administration (Admin)

### AD1. Create user accounts — Must
As an admin, I want to create accounts for faculty, so that only approved people can book.

- [ ] Admin enters name, email, and role; the system creates a temporary password and notifies the user (see F1).

### AD2. Manage users and access levels — Must
As an admin, I want to change a user's role or permissions, or deactivate them, so that access stays correct.

- [ ] Roles include at least: view only, faculty (book/edit own), admin.
- [ ] Deactivated users can't log in; their future reservations are handled (cancel or keep — to decide).

### AD3. Set bookable dates and closures — Must
As an admin, I want to choose which dates the room can be booked, so that the room can only be booked
when it's open.

- [ ] A **Bookable Dates** section on the admin dashboard lets me mark dates as bookable or blocked.
- [ ] I can select single dates, click and drag across dates, or select whole months or all weekends.
- [ ] I can set a **last bookable date**; nobody can book past it.
- [ ] Unsaved changes are highlighted, and I can discard them.
- [ ] Booking is blocked on blocked dates and after the last bookable date.
- [ ] Blocked dates show on the calendar (8 AM – 5 PM, "All day"), but not in Upcoming Events or check-in.
- [ ] If I block a date that already has reservations, I'm warned before saving. Confirming deletes
  those reservations **and their check-ins**.

> **In review** [#79]. This replaces the earlier idea of entering semester start/end dates and holidays
> separately.

### AD4. Manage any reservation — Must
As an admin, I want to edit or cancel anyone's reservation, so that I can settle conflicts and fix problems.

- [ ] The organizer is notified when an admin changes or cancels their reservation (once emails exist, R7).

### AD5. Manage event types — Should
As an admin, I want to add, rename, recolor, or remove event types, so that the list stays current.

- [ ] Colors must still meet contrast requirements (A1).

### AD6. Import dates from a document — Could
As an admin, I want to upload the college calendar (PDF/image) and have the dates filled in for me
(OCR), so that I don't have to type them.

- [ ] Admin reviews and confirms the dates before they're saved.

---

## Check-in & follow-up (Attendees, Faculty)

### C1. QR code and share link for each event — Must
As a faculty member, I want a QR code and a link for my event, so that attendees can check in when they
arrive without staff help [#60].

- [ ] Each event (and each occurrence of a recurring event) has its own link and QR code.
- [ ] The event pop-up has **QR Code** and **Share Link** buttons next to the Check In button.
- [ ] Share Link shows the event's link with a **Copy Link** button.
- [ ] Opening the link (or scanning the QR code) opens the calendar with that event's pop-up already open.
- [ ] The organizer can display or print the QR code.
- [ ] Check-ins are only accepted during a window around the event (exact window to decide, see below).

> **In progress** on `feature/Event-QR-Code` [#58], [#59], [#61], [#72]: event links, opening the event
> from a link, the QR code, and Copy Link are built.
>
> **Check-in window — not settled:** [#71] proposes 15 minutes before until 15 minutes after the event;
> [#50] says check-in opens 10 minutes before the start. Pick one.
>
> **Deleting vs. cancelling — proposed** [#71]:
> 1. Past events and events in progress can't be deleted.
> 2. An event with at least one check-in is marked **cancelled** instead of deleted, so its check-in
>    records are kept.
> 3. An event with no check-ins is deleted.
>
> Feedback: where would cancelled events be shown (e.g. a "Cancelled" section)? Should the organizer be
> warned that an event with no check-ins will be deleted? Note that blocking a date (AD3) currently
> deletes check-ins, which doesn't match rule 2.

### C2. Check in by scanning — Must
As an attendee, I want to scan the QR code and check in with my student email, so that my attendance is recorded.

- [ ] Works on a phone without installing an app or logging in.
- [ ] Asks only for name and student email. **No CSIDs or other student records.**
- [ ] The same email can't check in twice to the same event.
- [ ] Check-in is refused outside the allowed window, and the server enforces this, not just the page [#50].
- [ ] I see a confirmation that I'm checked in.

### C3. See who checked in — Must
As a faculty member, I want to see the check-in list for my event, so that I know who attended.

- [ ] Only the organizer and admins can see the list.
- [ ] Shows names, emails, and check-in times.

### C4. Automatic check-out — Should
As a faculty member, I want everyone checked out automatically when the event ends, so that nobody has to do it by hand.

- [ ] Open check-ins are closed at the event's end time.

### C5. After-event follow-up email — Should
As a faculty member, I want an email after my event asking whether it was held and whether there were
any issues, so that problems with the room get reported.

- [ ] Sent to the organizer only, shortly after the event ends.
- [ ] Links to a short form: "Was the event held?" (yes/no) and "Any issues or concerns?" (optional text).
- [ ] Admins can see the responses, especially reported issues.

---

## Accessibility & usability (Everyone)

### A1. Accessible to everyone — Must
As a user with a disability, I want the site to work with screen readers and have readable colors, so
that I can use it like anyone else.

- [ ] Meets WCAG 2.1 AA color contrast.
- [ ] Usable with keyboard only and with a screen reader (labels, headings, focus visible).
- [ ] The order of things for keyboard and screen-reader users matches what's on screen [#74].
- [ ] Animations (like the calendar zoom) turn off for people whose device is set to reduce motion [#63].
- [ ] Information is never shown by color alone.

> **Planned** [#67]: walk through every page to check accessibility and how it works on different devices.

### A2. Easy to use on any device — Must
As any user, I want the site to be simple and work on phones and computers, so that I can use it anywhere.

- [ ] Works on common phone and desktop screen sizes, with no sideways scrolling.
- [ ] Rows of buttons wrap instead of getting cut off on small screens, and the calendar resizes when the
  window does [#75].
- [ ] Booking a single event takes only a few steps.

---

## Later

Written down so the design leaves room for them; **not in current scope**.

- **L1. Student reservation requests** — As a student, I want to request a reservation on the website,
  so that faculty/admin can approve it for me. *(Students can't book directly: an employee must be present.)*
- **L2. Invitee emails** — As an organizer, I want to give an invitee list and have the system email
  them, so that I don't have to invite people separately.
- **L3. Text message reminders** — As an organizer, I want reminders by text as well as email.
- **L4. Open-time notifications** — As a faculty member, I want to be notified when time slots open
  up, so that I can grab them.
- **L5. Usage stats and surveys** — Usage reports (hours used, etc.) and optional surveys
  (including voluntary demographics) to show why the room needs funding.
  *(Data collection and analysis are out of scope for now.)*
- **L6. Private event access for invitees** — As an invited attendee, I want to see a private event's
  details, so that I know it's the right event. Options being researched [#66]: anyone with the link,
  anyone with the link who enters an invited email, or signing in with an SDCCD Microsoft account.
  *(Decided against for now in [#68]; the organizer emails attendees instead.)*

## Open questions

1. **Last-minute bookings:** can someone book a slot that starts in 10 minutes or less? If not, what's
   the minimum notice? *(Right now, any time after the current moment is allowed [#34].)*
2. **Winter and summer sessions:** is the room open during them, and with what hours? *(Admins can now
   mark any date as bookable, see AD3.)*
3. **How far ahead can people book?** Up to the admin's last bookable date (AD3). Is that enough, or is
   another limit needed?
4. **Maximum length of a reservation:** is there one?
5. **Landing page content:** confirm with Monica (see V5 and [#70]).
6. **Calendar on other pages:** does this mean embedding on other college sites, a link, or something else?
7. **Reminder timing:** how long before the event?
8. **Deactivated users:** what happens to their future reservations?
9. **Check-in window:** 15 minutes before and after [#71], or opening 10 minutes before the start [#50]?
10. **Check-in data:** how long are check-in records kept, and who besides the organizer can see them?
    When an event is deleted (or a series, or a blocked date), are its check-ins deleted or kept [#56]?
11. **Deleting events:** confirm the proposed delete vs. cancel rules in C1 [#71], and where cancelled
    events are shown.
12. **Private events and check-in:** can attendees check in to a private event the same way [#68]?
13. **Event type look:** is the color + label design too cluttered? Do we want icons or emoji [#69]?
14. Should admins be able to override unavailable days to create special reservations?

[#33]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/33
[#34]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/34
[#44]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/44
[#49]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/49
[#50]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/50
[#51]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/51
[#52]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/52
[#53]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/53
[#54]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/54
[#55]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/55
[#56]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/56
[#57]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/57
[#58]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/58
[#59]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/59
[#60]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/60
[#61]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/61
[#63]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/63
[#64]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/64
[#66]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/66
[#67]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/67
[#68]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/68
[#69]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/69
[#70]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/70
[#71]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/71
[#72]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/72
[#73]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/issues/73
[#74]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/74
[#75]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/75
[#76]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/76
[#77]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/77
[#78]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/78
[#79]: https://github.com/alex0091845/ReservationSystem-SDMesaCollegeBT/pull/79
