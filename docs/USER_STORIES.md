# User Stories

Requirements for the BT building room reservation system at San Diego Mesa College, written as
user stories. Based on the initial stakeholder meeting notes (since revised).

**Format:** each story has a priority and acceptance criteria (what "done" means).

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
| **Admin** | Room/system managers | Everything faculty can, plus manage users, permissions, semester dates, and any reservation |

Students cannot book for now, because an employee must be present at all times. The design should
leave room for that to change (see [Later](#later)).

## Booking rules

These apply to every reservation story below.

- The room can only be reserved **8:00 AM – 5:00 PM**.
- Reservations are only allowed **during regular semesters**. The room is closed on holidays.
- Bookings may not overlap. **First come, first served**; an admin can edit or cancel any booking to
  settle a conflict.
- The organizer is responsible for making sure participants are invited / allowed to attend.

---

## Viewing the calendar (Visitor)

### V1. View the calendar — Must
As a visitor, I want to see when the room is booked, so that I know what's happening and when it's free.

- [ ] The calendar is viewable without logging in.
- [ ] I can switch between **1 day and full week** views.
- [ ] Booked times, closed days, and hours outside 8 AM – 5 PM are clearly shown as unavailable.

### V2. See event details — Must
As a visitor, I want to open an event and see its details, so that I know whether it's relevant to me.

- [ ] Public events show title, type, time, and organizer.
- [ ] Private events show only as "Private event" with no private details.
  - Leo & Alex: Maybe by default, the event is labeled "Private event", but the organizer can choose to reveal the event name.

### V3. Tell event types apart — Must
As a visitor, I want event types (office hours, workshop, study group, class, department, etc.) to look
different, so that I can scan the calendar quickly.

- [ ] Each event type has its own color.
- [ ] Color is never the only signal — the type name or a label/icon is also shown (see A1).
  - Leo & Alex: Might be too cluttered? Might need more user feedback
- [ ] A legend explains the colors.

### V4. "Happening right now" — Must
As a visitor, I want to see what's going on in the room right now, so that I know if I can walk in.

- [ ] The landing page shows the current event (if any) and the next upcoming one(s).
- [ ] Private events show as "Private event", like in V2.

### V5. Landing page — Must
As a visitor, I want a clear landing page, so that I immediately see the most useful information.

- [ ] Shows today's schedule / "Happening right now".
- [ ] Shows the next upcoming event(s).
- [ ] Shows a check-in box for attendees to check in.
- [ ] Shows a telescopic view of the calendar (1 day / 1 week, or more).
- [ ] Final design, content, and arrangement to be confirmed with Monica/Allan (see [Open questions](#open-questions)).
  - Leo & Alex: Might try removing the month view on the left side since we already have access to the month view in the the main telescopic calendar view. Probably also move the Check-in box up above "Upcoming Events."
  - Leo & Alex: Also, relabel "Check-in" to "Happening Now", and for each event card (although there should only be one at a time), embed a "Check-in" button that opens the check-in form in a modal.
  - Leo & Alex: Something that might be worth considering: redesign the whole landing page so that it's more user friendly. For example, we might put the "Happening Now" section at the top (like a hero or a jumbotron) when you get to the landing page. If the user would like to see what events are coming up, they can scroll down to see the "Upcoming Events" section. And then if they'd like to see the calendar, they would just keep scrolling down. This would make the landing page more user friendly and easier to navigate.

### V6. QR code to the calendar — Should
As a visitor walking past the room, I want to scan a QR code posted at the door, so that I can open the
calendar on my phone.

- [ ] A printable QR code links to the calendar page.

### V7. Calendar on other pages — Could
As a department, I want to show the room calendar on our own web page, so that people don't have to go to
a separate site.

- [ ] The calendar can be embedded or linked from other pages (exact need to be clarified).

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
- [ ] I can log out from any page.
- [ ] Only logged-in faculty and admins see booking controls.

---

## Reservations (Faculty)

### R1. Book a reservation — Must
As a faculty member, I want to reserve the room for a time slot, so that I can hold my event.

- [ ] I enter: event title, event type, date, start/end time, public or private, and an optional
  description. Expected participant count is **not** required.
- [ ] I can mark it as "Reserved for class" or "Reserved for department".
- [ ] I can't pick times outside the [booking rules](#booking-rules).
- [ ] If the slot is already taken, I'm told so before submitting, not after.
- [ ] I see a confirmation when the booking succeeds.

### R2. Public vs. private events — Must
As a faculty member, I want to choose whether my event is public or private, so that I control who sees
the details.

- [ ] Public: details visible to all visitors.
- [ ] Private: shown to visitors only as "Private event" (see V2).

### R3. Recurring reservations — Must
As a faculty member, I want to book a repeating event (e.g. weekly office hours), so that I don't have to
book each week by hand.

- [ ] I can choose which days it repeats and when it ends.
- [ ] Repeats can't go past the end of the semester or land on holidays / closed days.
- [ ] If some dates conflict, I'm shown which ones before booking, and can book the rest.
- [ ] I can edit or cancel one occurrence or the whole series.

### R4. View my reservations — Must
As a faculty member, I want a list of my upcoming reservations, so that I can keep track of them.

- [ ] Shows only my reservations, soonest first.

### R5. Edit my reservation — Must
As a faculty member, I want to change my reservation's details or time, so that I can fix mistakes or
adjust plans.

- [ ] I can edit only my own reservations.
- [ ] Changes follow the same rules and conflict checks as a new booking.

### R6. Cancel my reservation — Must
As a faculty member, I want to cancel my reservation, so that the room is freed for others.

- [ ] I can cancel only my own reservations.
- [ ] I'm asked to confirm before it's cancelled.
- [ ] The time slot becomes available right away.

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

### AD3. Set semester dates and closures — Must
As an admin, I want to set semester start/end dates and holidays, so that the room can only be booked when it's open.

- [ ] Admin can add, edit, and remove semesters and closed days.
- [ ] Booking is blocked outside open semesters and on closed days.

### AD4. Manage any reservation — Must
As an admin, I want to edit or cancel anyone's reservation, so that I can settle conflicts and fix problems.

- [ ] The organizer is notified when an admin changes or cancels their reservation (once emails exist, R7).

### AD5. Manage event types — Should
As an admin, I want to add, rename, recolor, or remove event types, so that the list stays current.

- [ ] Colors must still meet contrast requirements (A1).

### AD6. Import semester dates from a document — Could
As an admin, I want to upload the college calendar (PDF/image) and have the dates filled in for me
(OCR), so that I don't have to type them.

- [ ] Admin reviews and confirms the dates before they're saved.

---

## Check-in & follow-up (Attendees, Faculty)

### C1. QR code for each event — Must
As a faculty member, I want a QR code for my event, so that attendees can check in when they arrive.

- [ ] Each event (and each occurrence of a recurring event) has its own QR code.
- [ ] The organizer can display or print it from the event page.
- [ ] The code only accepts check-ins during the event (exact window to decide).
  - Leo and I discussed a 15-minute window before and after the event. After brainstorming with Claude, here's how deletion might work:
    1. Past events and events currently in progress can't be deleted
    2. If an event has at least one check-in/deleted, it becomes cancelled
    3. If an event has no check-ins, it will be deleted

### C2. Check in by scanning — Must
As an attendee, I want to scan the QR code and check in with my student email, so that my attendance is recorded.

- [ ] Works on a phone without installing an app or logging in.
- [ ] Asks only for name and student email. **No CSIDs or other student records.**
- [ ] The same email can't check in twice to the same event.
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
- [ ] Information is never shown by color alone.

### A2. Easy to use on any device — Must
As any user, I want the site to be simple and work on phones and computers, so that I can use it anywhere.

- [ ] Works on common phone and desktop screen sizes.
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

## Open questions

1. **Last-minute bookings:** can someone book a slot that starts in 10 minutes or less? If not, what's the minimum notice?
2. **Winter and summer sessions:** is the room open during them, and with what hours?
3. **How far ahead can people book?** ("No time limit on scheduling" — does that mean any date in an open semester, or something else?)
4. **Maximum length of a reservation:** is there one?
5. **Landing page content:** confirm with Monica.
6. **Calendar on other pages:** does this mean embedding on other college sites, a link, or something else?
7. **Reminder timing:** how long before the event?
8. **Deactivated users:** what happens to their future reservations?
9. **Check-in window:** how early before the event can people check in, and how late after it starts?
10. **Check-in data:** how long are check-in records kept, and who besides the organizer can see them?
11. **Deleting events:** can an event be deleted after it has started, or after check-ins have been made? If so, what happens to the check-in data? What about cancelling events? (See C1.)
