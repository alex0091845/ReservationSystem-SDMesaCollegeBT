import { createSpecialReservation, getReservationAvailability, getSpecialReservations } from "../api.js";
import { renderEventTypeOptions } from "./eventTypeOptions.js";

const TIME_ZONE = "America/Los_Angeles";
const dateLabel = new Intl.DateTimeFormat("en-US", {
    timeZone: "UTC", weekday: "long", month: "long", day: "numeric", year: "numeric"
});
const shortDateLabel = new Intl.DateTimeFormat("en-US", {
    timeZone: "UTC", month: "short", day: "numeric", year: "numeric"
});
const timeLabel = new Intl.DateTimeFormat("en-US", { timeZone: TIME_ZONE, hour: "numeric", minute: "2-digit" });

function labelDate(value) {
    return dateLabel.format(new Date(`${value}T12:00:00Z`));
}

function errorMessage(error, fallback) {
    if (error?.status === 401 || error?.status === 403) {
        return "Only admins can create special reservations. Sign in again as an admin.";
    }

    return typeof error?.data?.error === "string" ? error.data.error : fallback;
}

// Admin-only booking on an unavailable date through POST /api/events/special. The date stays
// unavailable for everyone else; the backend and database repeat every check made here.
export function createSpecialReservations({ container, getUsers, getEventTypes, onCreated = () => {} }) {
    const form = container.querySelector("#specialReservationForm");
    const host = container.querySelector("#specialReservationHost");
    const date = container.querySelector("#specialReservationDate");
    const start = container.querySelector("#specialReservationStart");
    const end = container.querySelector("#specialReservationEnd");
    const type = container.querySelector("#specialReservationType");
    const access = container.querySelector("#specialReservationAccess");
    const warning = container.querySelector("#specialReservationWarning");
    const override = container.querySelector("#specialReservationOverride");
    const reason = container.querySelector("#specialReservationReason");
    const submit = container.querySelector("#specialReservationSubmit");
    const status = container.querySelector("#specialReservationStatus");
    const history = container.querySelector("#specialReservationHistory");
    let availability = null;
    let busy = false;

    function setStatus(message, kind = "") {
        status.textContent = message;
        status.classList.toggle("error", kind === "error");
        status.classList.toggle("success", kind === "success");
    }

    function dateState() {
        if (!date.value || !availability) return "unknown";
        if (!availability.last_bookable_date || date.value > availability.last_bookable_date) return "late";
        return (availability.invalid_dates || []).includes(date.value) ? "closed" : "open";
    }

    function update() {
        const state = dateState();
        const messages = {
            closed: `${date.value && labelDate(date.value)} is unavailable. Faculty cannot book it, and it stays ` +
                "unavailable after this reservation. Select the override and give a reason to book it anyway.",
            open: `${date.value && labelDate(date.value)} is open. Use the normal reservation form instead; ` +
                "a special reservation is only for unavailable dates.",
            late: `${date.value && labelDate(date.value)} is after the last bookable date. ` +
                "An override does not cover dates after it."
        };
        warning.hidden = !messages[state];
        warning.textContent = messages[state] || "";
        warning.classList.toggle("is-warning", state === "closed");
        override.disabled = busy || state !== "closed";
        if (state !== "closed") override.checked = false;
        submit.disabled = busy || state !== "closed" || !override.checked || !reason.value.trim();
    }

    function refresh() {
        const selectedHost = host.value;
        const people = getUsers()
            .filter(user => user.enabled !== false)
            .sort((a, b) => `${a.first_name} ${a.last_name}`.localeCompare(`${b.first_name} ${b.last_name}`));
        const placeholder = new Option("Select a host", "");
        placeholder.disabled = true;
        host.replaceChildren(placeholder, ...people.map(user => new Option(
            `${`${user.first_name || ""} ${user.last_name || ""}`.trim() || "Unnamed user"} (${user.email || "no email"})`,
            String(user.id)
        )));
        host.value = people.some(user => String(user.id) === selectedHost) ? selectedHost : "";
        renderEventTypeOptions(type, getEventTypes(), { selectedValue: type.value });
        update();
    }

    function renderHistory(rows) {
        history.replaceChildren();
        if (!rows.length) {
            const empty = document.createElement("p");
            empty.className = "faculty-user-empty";
            empty.textContent = "No special reservations yet.";
            history.appendChild(empty);
            return;
        }
        const list = document.createElement("ul");
        list.className = "faculty-user-list admin-closure-history";
        for (const row of rows) {
            const item = document.createElement("li");
            item.className = "admin-closure-history-item";
            const title = document.createElement("strong");
            title.textContent = `${row.event_title || "Untitled reservation"}, ${shortDateLabel.format(new Date(`${row.override_date}T12:00:00Z`))}, ` +
                `${timeLabel.format(new Date(row.event_start_time))} to ${timeLabel.format(new Date(row.event_end_time))}`;
            const detail = document.createElement("span");
            detail.textContent = `Reason: ${row.reason}. Approved by ${row.actor_email}.`;
            item.append(title, detail);
            list.appendChild(item);
        }
        history.appendChild(list);
    }

    async function loadHistory() {
        try {
            renderHistory(await getSpecialReservations());
        } catch (error) {
            history.textContent = "The override history could not be loaded.";
        }
    }

    async function load() {
        try {
            availability = await getReservationAvailability();
        } catch (error) {
            availability = null;
            setStatus("Bookable dates could not be loaded, so special reservations are unavailable right now.", "error");
        }
        refresh();
        await loadHistory();
    }

    [date, override, reason].forEach(input => input.addEventListener("input", update));
    override.addEventListener("change", update);

    form.addEventListener("submit", async event => {
        event.preventDefault();
        update();
        if (submit.disabled || !form.reportValidity()) return;
        if (end.value <= start.value) {
            setStatus("End time must be after start time.", "error");
            return;
        }
        busy = true;
        update();
        setStatus("Creating the special reservation...");
        const data = new FormData(form);
        try {
            await createSpecialReservation({
                overrideUnavailableDate: override.checked,
                reason: reason.value.trim(),
                event: {
                    host_user_id: Number(host.value),
                    start_time: `${date.value}T${start.value.slice(0, 5)}:00`,
                    end_time: `${date.value}T${end.value.slice(0, 5)}:00`,
                    event_type: type.value,
                    title: data.get("title"),
                    department: data.get("department"),
                    description: data.get("description"),
                    is_public: access.value === "open"
                }
            });
            const created = labelDate(date.value);
            form.reset();
            setStatus(`Special reservation created for ${created}. The date stays unavailable for everyone else.`, "success");
            await loadHistory();
            try {
                await onCreated();
            } catch (error) {
                console.error("The special reservation was saved, but the dashboard could not refresh:", error);
            }
        } catch (error) {
            setStatus(errorMessage(error, "Could not create the special reservation. Please try again."), "error");
        } finally {
            busy = false;
            update();
        }
    });

    return { load, refresh };
}
