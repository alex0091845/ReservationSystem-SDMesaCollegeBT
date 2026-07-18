import {
    CALENDAR_END_HOUR,
    CALENDAR_START_HOUR,
    formatShortDateRange,
    weekdayNames
} from "../utils/dateUtils.js";

const SLOT_MINUTES = 30;
const MS_PER_MINUTE = 60 * 1000;

export function createReservationTimePicker({
    container,
    summaryElement,
    startInput,
    endInput
}) {
    if (!container || !summaryElement || !startInput || !endInput) {
        return createNoopTimePicker();
    }

    let weekStart = getStartOfWeek(new Date());
    let selectedStart = parseLocalDateTimeInput(startInput.value);
    let selectedEnd = parseLocalDateTimeInput(endInput.value);
    let dragAnchor = null;
    let isDragging = false;

    container.classList.add("reservation-time-picker");

    function render() {
        container.innerHTML = "";

        const shell = document.createElement("div");
        shell.className = "reservation-time-picker-shell";

        const header = createHeader();
        const grid = createGrid();

        shell.append(header, grid);
        container.appendChild(shell);

        updateSelectionStyles();
        updateSummary();
    }

    function createHeader() {
        const header = document.createElement("div");
        header.className = "reservation-time-picker-header";

        const previousButton = createWeekButton("Previous week", "<");
        const nextButton = createWeekButton("Next week", ">");
        const title = document.createElement("div");
        title.className = "reservation-time-picker-title";
        title.textContent = formatShortDateRange(
            weekStart,
            addDays(weekStart, 6)
        );

        previousButton.addEventListener("click", () => {
            weekStart = addDays(weekStart, -7);
            render();
        });

        nextButton.addEventListener("click", () => {
            weekStart = addDays(weekStart, 7);
            render();
        });

        header.append(previousButton, title, nextButton);

        return header;
    }

    function createGrid() {
        const grid = document.createElement("div");
        grid.className = "reservation-time-picker-grid";

        grid.appendChild(createGridCorner());

        getWeekDates().forEach(date => {
            grid.appendChild(createDayHeader(date));
        });

        getSlotTimes().forEach(({ hour, minute }) => {
            grid.appendChild(createTimeLabel(hour, minute));

            getWeekDates().forEach(date => {
                grid.appendChild(createSlotCell(date, hour, minute));
            });
        });

        return grid;
    }

    function createGridCorner() {
        const corner = document.createElement("div");
        corner.className = "reservation-time-picker-corner";

        return corner;
    }

    function createDayHeader(date) {
        const dayHeader = document.createElement("div");
        dayHeader.className = "reservation-time-picker-day";

        const dayName = document.createElement("span");
        dayName.textContent = weekdayNames[date.getDay()];

        const dayNumber = document.createElement("strong");
        dayNumber.textContent = `${date.getMonth() + 1}/${date.getDate()}`;

        dayHeader.append(dayName, dayNumber);

        return dayHeader;
    }

    function createTimeLabel(hour, minute) {
        const label = document.createElement("div");
        label.className = "reservation-time-picker-time";
        label.textContent = formatTimeLabel(hour, minute);

        return label;
    }

    function createSlotCell(date, hour, minute) {
        const slotStart = new Date(
            date.getFullYear(),
            date.getMonth(),
            date.getDate(),
            hour,
            minute,
            0,
            0
        );
        const slotEnd = new Date(
            slotStart.getTime() + (SLOT_MINUTES * MS_PER_MINUTE)
        );
        const cell = document.createElement("button");

        cell.type = "button";
        cell.className = "reservation-time-picker-slot";
        cell.dataset.pickerCell = "true";
        cell.dataset.start = String(slotStart.getTime());
        cell.dataset.end = String(slotEnd.getTime());
        cell.setAttribute(
            "aria-label",
            `${formatDayLabel(slotStart)}, ${formatTimeLabel(hour, minute)}`
        );

        cell.addEventListener("pointerdown", event => {
            event.preventDefault();
            beginDrag(cell);
        });

        return cell;
    }

    function beginDrag(cell) {
        dragAnchor = getCellRange(cell);
        isDragging = true;
        applyDragRange(cell);

        document.addEventListener("pointermove", handlePointerMove);
        document.addEventListener("pointerup", endDrag, { once: true });
        document.addEventListener("pointercancel", endDrag, { once: true });
    }

    function handlePointerMove(event) {
        if (!isDragging) {
            return;
        }

        const hoveredCell = document
            .elementFromPoint(event.clientX, event.clientY)
            ?.closest("[data-picker-cell='true']");

        if (hoveredCell && container.contains(hoveredCell)) {
            applyDragRange(hoveredCell);
        }
    }

    function applyDragRange(cell) {
        const currentRange = getCellRange(cell);

        if (!dragAnchor || !currentRange) {
            return;
        }

        selectedStart = new Date(Math.min(
            dragAnchor.start.getTime(),
            currentRange.start.getTime()
        ));
        selectedEnd = new Date(Math.max(
            dragAnchor.end.getTime(),
            currentRange.end.getTime()
        ));

        syncInputs();
        updateSelectionStyles();
        updateSummary();
    }

    function endDrag() {
        isDragging = false;
        dragAnchor = null;

        document.removeEventListener("pointermove", handlePointerMove);
    }

    function setRange(startValue, endValue) {
        selectedStart = parseAnyDate(startValue);
        selectedEnd = parseAnyDate(endValue);

        if (selectedStart) {
            weekStart = getStartOfWeek(selectedStart);
        }

        syncInputs();
        render();
    }

    function setWeekFromDate(dateValue) {
        const date = parseAnyDate(dateValue) || new Date();

        weekStart = getStartOfWeek(date);
        render();
    }

    function clear() {
        selectedStart = null;
        selectedEnd = null;
        startInput.value = "";
        endInput.value = "";
        updateSelectionStyles();
        updateSummary();
    }

    function syncInputs() {
        startInput.value = selectedStart
            ? formatDateTimeLocalInput(selectedStart)
            : "";
        endInput.value = selectedEnd
            ? formatDateTimeLocalInput(selectedEnd)
            : "";
    }

    function updateSelectionStyles() {
        container.querySelectorAll("[data-picker-cell='true']").forEach(cell => {
            const range = getCellRange(cell);
            const isSelected = Boolean(
                selectedStart &&
                selectedEnd &&
                range &&
                range.start < selectedEnd &&
                range.end > selectedStart
            );

            cell.classList.toggle("selected", isSelected);
        });
    }

    function updateSummary() {
        if (!selectedStart || !selectedEnd) {
            summaryElement.textContent = "Select a day and drag across the time slots.";
            summaryElement.classList.add("empty");
            return;
        }

        summaryElement.classList.remove("empty");
        summaryElement.textContent = formatSelectedRange(
            selectedStart,
            selectedEnd
        );
    }

    function getWeekDates() {
        return Array.from({ length: 7 }, (_, index) => {
            return addDays(weekStart, index);
        });
    }

    render();

    return {
        clear,
        render,
        setRange,
        setWeekFromDate
    };
}

function createNoopTimePicker() {
    return {
        clear() {},
        render() {},
        setRange() {},
        setWeekFromDate() {}
    };
}

function createWeekButton(label, textContent) {
    const button = document.createElement("button");

    button.type = "button";
    button.className = "reservation-time-picker-nav";
    button.textContent = textContent;
    button.setAttribute("aria-label", label);

    return button;
}

function getSlotTimes() {
    const slots = [];

    for (let hour = CALENDAR_START_HOUR; hour < CALENDAR_END_HOUR; hour += 1) {
        slots.push({ hour, minute: 0 });
        slots.push({ hour, minute: 30 });
    }

    return slots;
}

function getCellRange(cell) {
    const start = Number(cell?.dataset.start);
    const end = Number(cell?.dataset.end);

    if (!Number.isFinite(start) || !Number.isFinite(end)) {
        return null;
    }

    return {
        start: new Date(start),
        end: new Date(end)
    };
}

function getStartOfWeek(date) {
    const weekStart = new Date(
        date.getFullYear(),
        date.getMonth(),
        date.getDate(),
        0,
        0,
        0,
        0
    );

    weekStart.setDate(weekStart.getDate() - weekStart.getDay());

    return weekStart;
}

function addDays(date, dayCount) {
    return new Date(
        date.getFullYear(),
        date.getMonth(),
        date.getDate() + dayCount,
        date.getHours(),
        date.getMinutes(),
        date.getSeconds(),
        date.getMilliseconds()
    );
}

function parseAnyDate(value) {
    if (!value) {
        return null;
    }

    if (value instanceof Date) {
        return Number.isNaN(value.getTime()) ? null : value;
    }

    return parseLocalDateTimeInput(value) || parseDate(value);
}

function parseLocalDateTimeInput(value) {
    if (typeof value !== "string" || !value) {
        return null;
    }

    const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/);

    if (!match) {
        return null;
    }

    const [, year, month, day, hour, minute] = match.map(Number);
    const date = new Date(year, month - 1, day, hour, minute, 0, 0);

    return Number.isNaN(date.getTime()) ? null : date;
}

function parseDate(value) {
    const date = new Date(value);

    return Number.isNaN(date.getTime()) ? null : date;
}

function formatDateTimeLocalInput(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    const hour = String(date.getHours()).padStart(2, "0");
    const minute = String(date.getMinutes()).padStart(2, "0");

    return `${year}-${month}-${day}T${hour}:${minute}`;
}

function formatSelectedRange(start, end) {
    const sameDay =
        start.getFullYear() === end.getFullYear() &&
        start.getMonth() === end.getMonth() &&
        start.getDate() === end.getDate();

    if (sameDay) {
        return `${formatDayLabel(start)}, ${formatTimeLabel(start.getHours(), start.getMinutes())} - ${formatTimeLabel(end.getHours(), end.getMinutes())}`;
    }

    return `${formatDayLabel(start)}, ${formatTimeLabel(start.getHours(), start.getMinutes())} - ${formatDayLabel(end)}, ${formatTimeLabel(end.getHours(), end.getMinutes())}`;
}

function formatDayLabel(date) {
    return date.toLocaleDateString([], {
        weekday: "short",
        month: "short",
        day: "numeric"
    });
}

function formatTimeLabel(hour, minute) {
    const displayHour = hour % 12 || 12;
    const modifier = hour >= 12 ? "PM" : "AM";

    return `${displayHour}:${String(minute).padStart(2, "0")} ${modifier}`;
}
