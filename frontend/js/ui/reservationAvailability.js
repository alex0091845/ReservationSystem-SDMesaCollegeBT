import { getReservationAvailability, saveReservationAvailability } from "../api.js";
import { monthNames } from "../utils/dateUtils.js";

const TIME_ZONE = "America/Los_Angeles";
const pacificDate = new Intl.DateTimeFormat("en-CA", {
    timeZone: TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit"
});
const dateLabel = new Intl.DateTimeFormat("en-US", {
    timeZone: "UTC", month: "short", day: "numeric", year: "numeric"
});

function dateKey(date) {
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}

function localDateKey(date) {
    const parts = Object.fromEntries(pacificDate.formatToParts(date).map(part => [part.type, part.value]));
    return `${parts.year}-${parts.month}-${parts.day}`;
}

function parseDate(value) {
    return new Date(`${value}T12:00:00Z`);
}

function datesBetween(from, to) {
    const dates = [];
    for (let date = parseDate(from); dateKey(date) <= to; date.setUTCDate(date.getUTCDate() + 1)) {
        dates.push(dateKey(date));
    }
    return dates;
}

export function createReservationAvailability({ container, getReservations, onSaved = () => {} }) {
    const cutoff = container.querySelector("#availabilityCutoff");
    const savedCutoff = container.querySelector("#availabilitySavedCutoff");
    const save = container.closest("section").querySelector("#availabilitySave");
    const discard = container.querySelector("#availabilityDiscard");
    const selectAll = container.querySelector("#availabilitySelectAll");
    const weekends = container.querySelector("#availabilityWeekends");
    const previous = container.querySelector("#availabilityPreviousYear");
    const next = container.querySelector("#availabilityNextYear");
    const title = container.querySelector("#availabilityYear");
    const monthSelect = container.querySelector("#availabilityMonth");
    const grid = container.querySelector("#availabilityGrid");
    const reload = container.querySelector("#availabilityReload");
    const confirmation = document.getElementById("availabilityConfirmDialog");
    const confirmTitle = confirmation.querySelector("#availabilityConfirmTitle");
    const confirmMessage = confirmation.querySelector("#availabilityConfirmMessage");
    const confirmHint = confirmation.querySelector("#availabilityConfirmHint");
    const confirmWarning = confirmation.querySelector("#availabilityConfirmWarning");
    const confirmCancel = confirmation.querySelector("#availabilityConfirmCancel");
    const confirmAccept = confirmation.querySelector("#availabilityConfirmAccept");
    const confirmAlternative = confirmation.querySelector("#availabilityConfirmAlternative");
    let pendingAction = null;
    let pendingAlternativeAction = null;
    let confirmationTrigger = null;
    let year = Number(localDateKey(new Date()).slice(0, 4));
    let mobileMonth = Number(localDateKey(new Date()).slice(5, 7)) - 1;
    let lastDate = null;
    let invalidDates = new Set();
    let savedLastDate = null;
    let savedInvalidDates = new Set();
    let version = 0;
    let loaded = false;
    let busy = false;
    let dirty = false;
    let drag = null;
    let touchTap = null;
    let anchor = null;

    const today = () => localDateKey(new Date());
    const validCutoff = () => Boolean(lastDate) && cutoff.checkValidity();
    const editable = value => validCutoff() && value >= today() && value <= lastDate;
    const usable = () => loaded && !busy && validCutoff();
    const allDates = () => validCutoff() ? datesBetween(today(), lastDate) : [];
    const availableOn = (value, last, invalid) => Boolean(last) && value >= today() && value <= last && !invalid.has(value);

    function hasUnsavedChanges() {
        if (!loaded) return false;
        if (lastDate !== savedLastDate || cutoff.value !== (savedLastDate || "")) return true;
        const dates = [...invalidDates].filter(value => lastDate && value <= lastDate);
        return dates.length !== savedInvalidDates.size || dates.some(value => !savedInvalidDates.has(value));
    }

    function weekendDates() {
        return datesBetween(`${year}-01-01`, `${year}-12-31`).filter(value => {
            return editable(value) && [0, 6].includes(parseDate(value).getUTCDay());
        });
    }

    function setDates(dates, allow) {
        for (const value of dates) {
            if (!editable(value)) continue;
            if (allow) invalidDates.delete(value);
            else invalidDates.add(value);
        }
    }

    function syncCheckbox(checkbox, dates) {
        const count = dates.filter(value => !invalidDates.has(value)).length;
        checkbox.checked = dates.length > 0 && count === dates.length;
        checkbox.indeterminate = count > 0 && count < dates.length;
        checkbox.disabled = !usable() || dates.length === 0;
    }

    function affectedReservations({ dates = null, cutoffDate = lastDate, blocked = invalidDates } = {}) {
        const selectedDates = dates ? new Set(dates) : null;
        return getReservations().filter(reservation => {
            if (reservation.is_closure) return false;
            const start = new Date(reservation.start_time ?? reservation.start);
            const end = new Date(reservation.end_time ?? reservation.end);
            if (Number.isNaN(start.getTime()) || !(end > new Date())) return false;
            const date = localDateKey(start);
            return selectedDates ? selectedDates.has(date) : Boolean(cutoffDate) && (date > cutoffDate || blocked.has(date));
        }).sort((first, second) => new Date(first.start_time) - new Date(second.start_time));
    }

    function update() {
        dirty = hasUnsavedChanges();
        cutoff.disabled = !loaded || busy;
        const cutoffChanged = loaded && cutoff.value !== (savedLastDate || "");
        cutoff.classList.toggle("is-modified", cutoffChanged);
        savedCutoff.hidden = !cutoffChanged;
        savedCutoff.textContent = cutoffChanged ? `Saved: ${savedLastDate ? dateLabel.format(parseDate(savedLastDate)) : "not set"}` : "";
        if (cutoffChanged) cutoff.setAttribute("aria-describedby", savedCutoff.id);
        else cutoff.removeAttribute("aria-describedby");
        for (const button of grid.querySelectorAll("[data-date]")) {
            const value = button.dataset.date;
            const allowed = editable(value) && !invalidDates.has(value);
            button.disabled = !usable() || !editable(value);
            button.classList.toggle("is-available", allowed);
            button.classList.toggle("is-blocked", !allowed);
            button.classList.toggle("is-outside-window", value < today() || !lastDate || value > lastDate);
            const savedAllowed = availableOn(value, savedLastDate, savedInvalidDates);
            const modified = loaded && availableOn(value, lastDate, invalidDates) !== savedAllowed;
            button.classList.toggle("is-modified", modified);
            button.setAttribute("aria-pressed", String(allowed));
            const state = value < today() ? "past date" : !lastDate || value > lastDate
                ? "outside booking window" : allowed ? "available" : "blocked";
            const label = `${button.dataset.label}, ${state}${modified ? `, unsaved edit; saved as ${savedAllowed ? "available" : "blocked"}` : ""}`;
            button.setAttribute("aria-label", label);
            button.title = label;
        }
        for (const month of grid.querySelectorAll("[data-month]")) {
            month.dataset.activeMonth = String(Number(month.dataset.month) === mobileMonth);
            const dates = [...month.querySelectorAll("[data-date]")].map(button => button.dataset.date).filter(editable);
            syncCheckbox(month.querySelector("input"), dates);
        }
        syncCheckbox(selectAll, allDates());
        syncCheckbox(weekends, weekendDates());
        weekends.setAttribute("aria-label", `Select or deselect weekends in ${year}`);
        previous.disabled = busy || !loaded || year <= Number(today().slice(0, 4));
        next.disabled = busy || !loaded || !lastDate || year >= Number(lastDate.slice(0, 4));
        save.disabled = !usable() || (!dirty && affectedReservations().length === 0);
        discard.disabled = !loaded || busy || !dirty;
        container.setAttribute("aria-busy", String(busy));
    }

    function render() {
        title.textContent = String(year);
        monthSelect.value = String(mobileMonth);
        grid.replaceChildren();
        for (let month = 0; month < 12; month++) {
            const card = document.createElement("section");
            card.className = "year-month";
            card.dataset.month = String(month);
            const heading = document.createElement("label");
            heading.className = "availability-month-name";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.dataset.selectMonth = String(month);
            checkbox.setAttribute("aria-label", `Select all dates in ${monthNames[month]} ${year}`);
            heading.append(document.createTextNode(monthNames[month]), checkbox);
            const days = document.createElement("div");
            days.className = "year-month-days";
            const offset = new Date(Date.UTC(year, month, 1)).getUTCDay();
            const length = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
            for (let index = 0; index < 42; index++) {
                const day = index - offset + 1;
                if (day < 1 || day > length) {
                    const empty = document.createElement("span");
                    empty.className = "year-day is-empty";
                    days.append(empty);
                    continue;
                }
                const button = document.createElement("button");
                const date = new Date(Date.UTC(year, month, day, 12));
                button.type = "button";
                button.className = "year-day";
                button.dataset.date = dateKey(date);
                button.dataset.label = dateLabel.format(date);
                button.textContent = String(day);
                days.append(button);
            }
            card.append(heading, days);
            grid.append(card);
        }
        update();
    }

    function clearError() {
        reload.hidden = true;
        reload.removeAttribute("title");
    }

    function showError(error, showDialog = false) {
        const message = error.data?.error || error.message || "Could not load or save bookable dates.";
        console.error("Bookable dates request failed:", error);
        reload.hidden = false;
        reload.title = message;
        if (showDialog) {
            confirmAction({
                title: !error.status || error.status >= 500 ? "Save not confirmed" : "Changes not saved",
                message,
                hint: error.status === 409 || !error.status || error.status >= 500
                    ? "Your local edits are still here. Reload the saved dates before trying again."
                    : "Your unsaved edits are still here.",
                label: "OK",
                action: () => {}
            });
        }
    }

    function changed() {
        update();
    }

    function finishDateSelection(before, after) {
        const blocked = [...after].filter(value => !before.has(value) && editable(value));
        const affected = affectedReservations({ dates: blocked });
        if (!affected.length) {
            invalidDates = after;
            changed();
            return;
        }
        invalidDates = before;
        update();
        confirmAction({
            title: "Make dates unavailable?",
            message: `Make ${blocked.length} date${blocked.length === 1 ? "" : "s"} unavailable?`,
            hint: "These edits take effect only after you save changes.",
            label: "Make unavailable",
            destructive: true,
            warning: affected,
            action: () => { invalidDates = after; changed(); }
        });
    }

    function applySettings(settings) {
        lastDate = settings.last_bookable_date;
        invalidDates = new Set(settings.invalid_dates);
        savedLastDate = lastDate;
        savedInvalidDates = new Set(invalidDates);
        version = settings.version;
        cutoff.value = lastDate || "";
        cutoff.min = today();
        loaded = true;
        dirty = false;
        anchor = null;
    }

    async function load() {
        busy = true;
        clearError();
        update();
        try {
            applySettings(await getReservationAvailability());
            year = Math.max(Number(today().slice(0, 4)), Number((lastDate || today()).slice(0, 4)));
            mobileMonth = year === Number(today().slice(0, 4)) ? Number(today().slice(5, 7)) - 1 : 0;
        } catch (error) {
            loaded = false;
            showError(error);
        } finally {
            busy = false;
            render();
        }
    }

    function preview(to) {
        if (drag.to === to) return;
        drag.to = to;
        invalidDates = new Set(drag.before);
        const [fromDate, toDate] = [drag.from, to].sort();
        setDates(datesBetween(fromDate, toDate), drag.allow);
        update();
    }

    grid.addEventListener("pointerdown", event => {
        const day = event.target.closest("[data-date]");
        if (!day || day.disabled || !event.isPrimary || event.button !== 0) return;
        touchTap = event.pointerType === "touch" ? day : null;
        if (touchTap) return;
        event.preventDefault();
        day.focus({ preventScroll: true });
        const value = day.dataset.date;
        drag = { from: event.shiftKey && anchor ? anchor : value, allow: invalidDates.has(value), before: new Set(invalidDates) };
        grid.setPointerCapture(event.pointerId);
        preview(value);
    });
    grid.addEventListener("pointermove", event => {
        if (!drag) return;
        const day = document.elementFromPoint(event.clientX, event.clientY)?.closest("[data-date]");
        if (day && grid.contains(day)) preview(day.dataset.date);
    });
    grid.addEventListener("pointerup", () => {
        if (!drag) return;
        anchor = drag.from;
        const before = drag.before;
        const after = new Set(invalidDates);
        drag = null;
        finishDateSelection(before, after);
    });
    function cancelDrag() {
        if (!drag) return;
        invalidDates = drag.before;
        drag = null;
        update();
    }
    grid.addEventListener("pointercancel", () => {
        touchTap = null;
        cancelDrag();
    });
    grid.addEventListener("lostpointercapture", cancelDrag);
    container.addEventListener("keydown", event => {
        if (event.key !== "Escape") return;
        touchTap = null;
        cancelDrag();
    });
    grid.addEventListener("click", event => {
        const day = event.target.closest("[data-date]");
        const isTouchClick = event.pointerType === "touch" || touchTap === day;
        touchTap = null;
        if (!day || day.disabled || (event.detail !== 0 && !isTouchClick)) return;
        const value = day.dataset.date;
        const dates = event.shiftKey && anchor ? datesBetween(...[anchor, value].sort()) : [value];
        const before = new Set(invalidDates);
        setDates(dates, invalidDates.has(value));
        anchor = value;
        finishDateSelection(before, new Set(invalidDates));
    });
    grid.addEventListener("change", event => {
        if (!event.target.matches("[data-select-month]")) return;
        const month = event.target.closest("[data-month]");
        const dates = [...month.querySelectorAll("[data-date]")].map(button => button.dataset.date);
        confirmBulkSelection(dates, { month: Number(month.dataset.month) });
    });
    function confirmAction({ title, message, hint, label, destructive = false, action, alternative = null, warning = [] }) {
        if (confirmation.open) return;
        confirmTitle.textContent = title;
        confirmMessage.textContent = message;
        confirmHint.textContent = hint;
        confirmWarning.hidden = warning.length === 0;
        const planned = warning.slice(0, 3).map(event =>
            `${event.title || "Untitled reservation"} (${dateLabel.format(parseDate(localDateKey(new Date(event.start_time))))})`
        );
        if (warning.length > 3) planned.push(`And ${warning.length - 3} more.`);
        const cancellation = alternative ? "Making dates unavailable will cancel" : "This will cancel";
        confirmWarning.textContent = warning.length ? `${cancellation} ${warning.length} reservation${warning.length === 1 ? "" : "s"}.\n${planned.join("\n")}` : "";
        confirmAccept.textContent = label;
        confirmAccept.className = destructive ? "reservation-danger-btn" : "reservation-primary-btn";
        pendingAction = action;
        confirmAlternative.hidden = !alternative;
        confirmAlternative.textContent = alternative?.label || "";
        pendingAlternativeAction = alternative?.action || null;
        confirmationTrigger = document.activeElement;
        confirmation.showModal();
        confirmCancel.focus();
    }
    function finishConfirmation(action) {
        pendingAction = null;
        pendingAlternativeAction = null;
        confirmation.close();
        action?.();
    }
    confirmAccept.addEventListener("click", () => finishConfirmation(pendingAction));
    confirmAlternative.addEventListener("click", () => finishConfirmation(pendingAlternativeAction));
    confirmCancel.addEventListener("click", () => confirmation.close());
    confirmation.querySelector("#availabilityConfirmClose").addEventListener("click", () => confirmation.close());
    confirmation.addEventListener("close", () => {
        if (confirmation.open) return;
        pendingAction = null;
        pendingAlternativeAction = null;
        if (confirmationTrigger?.isConnected) {
            (confirmationTrigger.disabled ? cutoff : confirmationTrigger).focus({ preventScroll: true });
        }
        confirmationTrigger = null;
    });
    confirmation.addEventListener("keydown", event => {
        if (event.key !== "Tab") return;
        const buttons = [...confirmation.querySelectorAll("button:not(:disabled):not([hidden])")];
        const first = buttons[0];
        const last = buttons.at(-1);
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });
    confirmation.addEventListener("click", event => {
        if (event.target !== confirmation) return;
        const bounds = confirmation.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) {
            confirmation.close();
        }
    });

    function confirmBulkSelection(dates, { weekendsOnly = false, month = null } = {}) {
        if (!usable()) {
            update();
            return;
        }
        const count = dates.filter(editable).length;
        update();
        if (!count) return;
        const monthLabel = month === null ? null : `${monthNames[month]} ${year}`;
        const scope = monthLabel ? `in ${monthLabel}` : weekendsOnly ? `on weekends in ${year}`
            : `from ${dateLabel.format(parseDate(today()))} through ${dateLabel.format(parseDate(lastDate))}`;
        const applySelection = allow => () => {
            if (!usable()) return;
            setDates(dates, allow);
            changed();
        };
        confirmAction({
            title: monthLabel ? `${monthNames[month]} availability` : weekendsOnly ? "Weekend availability" : "Date availability",
            message: monthLabel ? `Make all dates in ${monthLabel} available or unavailable?`
                : weekendsOnly ? "Make all weekends available or unavailable?" : "Make all dates available or unavailable?",
            hint: `Applies to ${count} date${count === 1 ? "" : "s"} ${scope}. Changes take effect after you save.`,
            label: "Make available",
            action: applySelection(true),
            alternative: { label: "Make unavailable", action: applySelection(false) },
            warning: affectedReservations({ dates: dates.filter(editable) })
        });
    }
    selectAll.addEventListener("change", () => confirmBulkSelection(allDates()));
    weekends.addEventListener("change", () => confirmBulkSelection(weekendDates(), { weekendsOnly: true }));
    discard.addEventListener("click", () => {
        if (!loaded || busy || !dirty) return;
        confirmAction({
            title: "Discard changes?",
            message: "Discard all unsaved date and last bookable date changes?",
            hint: "The calendar will return to the saved settings.",
            label: "Discard changes",
            destructive: true,
            action: discardChanges
        });
    });
    function discardChanges() {
        if (!loaded || busy || !dirty) return;
        cancelDrag();
        applySettings({ last_bookable_date: savedLastDate, invalid_dates: [...savedInvalidDates], version });
        year = Math.max(Number(today().slice(0, 4)), Math.min(year, Number((lastDate || today()).slice(0, 4))));
        if (year === Number((lastDate || today()).slice(0, 4))) {
            mobileMonth = Math.min(mobileMonth, Number((lastDate || today()).slice(5, 7)) - 1);
        }
        clearError();
        render();
    }
    cutoff.addEventListener("change", () => {
        if (!cutoff.reportValidity()) {
            update();
            return;
        }
        const nextDate = cutoff.value;
        if (nextDate === lastDate) return;
        const proposedDates = new Set(invalidDates);
        // Newly exposed dates stay closed until the admin selects them
        const from = lastDate && lastDate >= today()
            ? dateKey(new Date(parseDate(lastDate).getTime() + 86400000)) : today();
        if (!lastDate || nextDate > lastDate) {
            datesBetween(from, nextDate).forEach(value => proposedDates.add(value));
        }
        const applyCutoff = () => {
            lastDate = nextDate;
            invalidDates = proposedDates;
            cutoff.value = lastDate;
            year = Math.max(Number(today().slice(0, 4)), Math.min(year, Number(lastDate.slice(0, 4))));
            if (year === Number(lastDate.slice(0, 4))) {
                mobileMonth = Math.min(mobileMonth, Number(lastDate.slice(5, 7)) - 1);
            }
            anchor = null;
            render();
        };
        const affected = affectedReservations({ cutoffDate: nextDate, blocked: proposedDates });
        if (!affected.length) {
            applyCutoff();
            return;
        }
        cutoff.value = lastDate || "";
        update();
        confirmAction({
            title: "Change last bookable date?",
            message: `Set the last bookable date to ${dateLabel.format(parseDate(nextDate))}?`,
            hint: "Dates after this cutoff will be unavailable. Changes take effect after you save.",
            label: "Change date",
            destructive: true,
            warning: affected,
            action: applyCutoff
        });
    });
    function changeYear(offset) {
        year += offset;
        mobileMonth = year === Number(today().slice(0, 4)) ? Number(today().slice(5, 7)) - 1 : 0;
        anchor = null;
        render();
    }
    previous.addEventListener("click", () => changeYear(-1));
    next.addEventListener("click", () => changeYear(1));
    monthSelect.addEventListener("change", () => {
        mobileMonth = Number(monthSelect.value);
        update();
    });
    monthNames.forEach((name, month) => {
        const option = document.createElement("option");
        option.value = String(month);
        option.textContent = name;
        monthSelect.appendChild(option);
    });
    async function saveChanges(confirmedCancellations = []) {
        if (!usable() || !cutoff.reportValidity()) return;
        busy = true;
        clearError();
        update();
        try {
            const saved = await saveReservationAvailability({
                last_bookable_date: lastDate,
                invalid_dates: [...invalidDates].filter(value => value <= lastDate).sort(),
                version,
                confirmed_cancellations: confirmedCancellations
            });
            applySettings(saved);
            try {
                await onSaved();
            } catch (error) {
                console.error("Bookable dates were saved, but the event list could not refresh:", error);
            }
        } catch (error) {
            const affected = error.data?.conflicting_reservations;
            if (error.status === 409 && Array.isArray(affected) && affected.length) {
                confirmAction({
                    title: "Cancel planned reservations?",
                    message: "Saving these availability changes will cancel the reservations below.",
                    hint: "This permanently deletes the reservations and their check-ins.",
                    label: "Save and cancel reservations",
                    destructive: true,
                    warning: affected,
                    action: () => saveChanges(affected.map(event => event.id))
                });
            } else {
                showError(error, true);
            }
        } finally {
            busy = false;
            update();
        }
    }
    save.addEventListener("click", () => saveChanges());
    reload.addEventListener("click", () => {
        if (!dirty) {
            load();
            return;
        }
        confirmAction({
            title: "Reload saved dates?",
            message: "Reloading will discard your unsaved date and last bookable date changes.",
            hint: "The calendar will return to the saved settings.",
            label: "Reload",
            destructive: true,
            action: load
        });
    });
    window.addEventListener("beforeunload", event => {
        if (!dirty) return;
        event.preventDefault();
        event.returnValue = "";
    });
    render();
    return { load, refresh: update };
}
