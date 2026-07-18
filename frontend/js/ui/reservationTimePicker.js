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
    let selectedRanges = normalizeRanges([{
        start: parseLocalDateTimeInput(startInput.value),
        end: parseLocalDateTimeInput(endInput.value)
    }]);
    let dragAnchor = null;
    let dragPreviewRanges = [];
    let draggedRangeIndex = -1;
    let hasDraggedAcrossCells = false;
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

        grid.appendChild(createTimeBoundaryLabel());

        getWeekDates().forEach(() => {
            const boundaryCell = document.createElement("div");

            boundaryCell.className = "reservation-time-picker-boundary-cell";
            grid.appendChild(boundaryCell);
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
        const startText = document.createElement("span");

        label.className = "reservation-time-picker-time";
        startText.className = "reservation-time-picker-time-start";
        startText.textContent = formatTimeLabel(hour, minute);
        label.append(startText);

        return label;
    }

    function createTimeBoundaryLabel() {
        const label = document.createElement("div");
        const labelText = document.createElement("span");

        label.className = "reservation-time-picker-time reservation-time-picker-boundary-label";
        labelText.className = "reservation-time-picker-time-start";
        labelText.textContent = formatTimeLabel(CALENDAR_END_HOUR, 0);
        label.append(labelText);

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
            `${formatDayLabel(slotStart)}, ${formatSlotRangeLabel(slotStart, slotEnd)}`
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
        dragPreviewRanges = dragAnchor ? [dragAnchor] : [];
        draggedRangeIndex = findRangeIndexForCell(dragAnchor, selectedRanges);
        hasDraggedAcrossCells = false;
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

        hasDraggedAcrossCells =
            hasDraggedAcrossCells ||
            dragAnchor.start.getTime() !== currentRange.start.getTime();
        dragPreviewRanges = buildDailyDragRanges(dragAnchor, currentRange);

        syncInputs();
        updateSelectionStyles();
        updateSummary();
    }

    function endDrag() {
        if (dragPreviewRanges.length > 0) {
            let nextRanges = [...selectedRanges];

            if (draggedRangeIndex >= 0) {
                nextRanges.splice(draggedRangeIndex, 1);
            }

            if (draggedRangeIndex < 0 || hasDraggedAcrossCells) {
                nextRanges.push(...dragPreviewRanges);
            }

            selectedRanges = normalizeRanges(nextRanges);
            syncInputs();
            updateSelectionStyles();
            updateSummary();
        }

        isDragging = false;
        dragAnchor = null;
        dragPreviewRanges = [];
        draggedRangeIndex = -1;
        hasDraggedAcrossCells = false;

        document.removeEventListener("pointermove", handlePointerMove);
    }

    function setRange(startValue, endValue) {
        const selectedStart = parseAnyDate(startValue);
        const selectedEnd = parseAnyDate(endValue);

        if (selectedStart) {
            weekStart = getStartOfWeek(selectedStart);
        }

        selectedRanges = normalizeRanges([{
            start: selectedStart,
            end: selectedEnd
        }]);
        syncInputs();
        render();
    }

    function setRanges(ranges = []) {
        selectedRanges = normalizeRanges(ranges);

        if (selectedRanges[0]?.start) {
            weekStart = getStartOfWeek(selectedRanges[0].start);
        }

        syncInputs();
        render();
    }

    function getRanges() {
        return selectedRanges.map(range => ({
            start: new Date(range.start),
            end: new Date(range.end)
        }));
    }

    function setWeekFromDate(dateValue) {
        const date = parseAnyDate(dateValue) || new Date();

        weekStart = getStartOfWeek(date);
        render();
    }

    function clear() {
        selectedRanges = [];
        startInput.value = "";
        endInput.value = "";
        updateSelectionStyles();
        updateSummary();
    }

    function syncInputs() {
        const primaryRange = selectedRanges[0] || dragPreviewRanges[0];
        const previousStartValue = startInput.value;
        const previousEndValue = endInput.value;

        startInput.value = primaryRange?.start
            ? formatDateTimeLocalInput(primaryRange.start)
            : "";
        endInput.value = primaryRange?.end
            ? formatDateTimeLocalInput(primaryRange.end)
            : "";

        if (startInput.value !== previousStartValue) {
            dispatchFieldChange(startInput);
        }

        if (endInput.value !== previousEndValue) {
            dispatchFieldChange(endInput);
        }
    }

    function updateSelectionStyles() {
        const renderedRanges = getRenderedSelectionRanges();

        container.querySelectorAll("[data-picker-cell='true']").forEach(cell => {
            const range = getCellRange(cell);
            const isSelected = Boolean(range && renderedRanges.some(selectedRange => {
                return rangesOverlap(range, selectedRange);
            }));

            cell.classList.toggle("selected", isSelected);
        });
    }

    function updateSummary() {
        const renderedRanges = getRenderedSelectionRanges();

        if (renderedRanges.length === 0) {
            summaryElement.textContent = "Select one or more time blocks.";
            summaryElement.classList.add("empty");
            return;
        }

        summaryElement.classList.remove("empty");
        summaryElement.textContent = formatSelectedRanges(renderedRanges);
    }

    function getRenderedSelectionRanges() {
        if (!isDragging || dragPreviewRanges.length === 0) {
            return selectedRanges;
        }

        const renderedRanges = [...selectedRanges];

        if (draggedRangeIndex >= 0) {
            renderedRanges.splice(draggedRangeIndex, 1);
        }

        if (draggedRangeIndex < 0 || hasDraggedAcrossCells) {
            renderedRanges.push(...dragPreviewRanges);
        }

        return normalizeRanges(renderedRanges);
    }

    function getWeekDates() {
        return Array.from({ length: 7 }, (_, index) => {
            return addDays(weekStart, index);
        });
    }

    render();

    return {
        clear,
        getRanges,
        render,
        setRange,
        setRanges,
        setWeekFromDate
    };
}

function createNoopTimePicker() {
    return {
        clear() {},
        getRanges() {
            return [];
        },
        render() {},
        setRange() {},
        setRanges() {},
        setWeekFromDate() {}
    };
}

function dispatchFieldChange(input) {
    if (!input) {
        return;
    }

    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
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

function buildDailyDragRanges(anchorRange, currentRange) {
    const startDay = stripTime(new Date(Math.min(
        anchorRange.start.getTime(),
        currentRange.start.getTime()
    )));
    const endDay = stripTime(new Date(Math.max(
        anchorRange.start.getTime(),
        currentRange.start.getTime()
    )));
    const startMinutes = Math.min(
        getMinutesSinceMidnight(anchorRange.start),
        getMinutesSinceMidnight(currentRange.start)
    );
    const endMinutes = Math.max(
        getMinutesSinceMidnight(anchorRange.end),
        getMinutesSinceMidnight(currentRange.end)
    );
    const ranges = [];

    for (
        let date = new Date(startDay);
        date <= endDay;
        date = addDays(date, 1)
    ) {
        ranges.push({
            start: createDateAtMinutes(date, startMinutes),
            end: createDateAtMinutes(date, endMinutes)
        });
    }

    return ranges;
}

function findRangeIndexForCell(cellRange, selectedRanges = []) {
    if (!cellRange) {
        return -1;
    }

    return selectedRanges.findIndex(range => rangesOverlap(range, cellRange));
}

function rangesOverlap(firstRange, secondRange) {
    return firstRange.start < secondRange.end &&
        firstRange.end > secondRange.start;
}

function normalizeRanges(ranges = []) {
    const validRanges = ranges
        .map(range => ({
            start: parseAnyDate(range?.start),
            end: parseAnyDate(range?.end)
        }))
        .filter(range => {
            return range.start && range.end && range.end > range.start;
        })
        .sort((firstRange, secondRange) => {
            return firstRange.start - secondRange.start;
        });

    return validRanges.reduce((mergedRanges, range) => {
        const lastRange = mergedRanges[mergedRanges.length - 1];

        if (
            lastRange &&
            isSameDay(lastRange.start, range.start) &&
            range.start <= lastRange.end
        ) {
            lastRange.end = new Date(Math.max(
                lastRange.end.getTime(),
                range.end.getTime()
            ));
            return mergedRanges;
        }

        mergedRanges.push({
            start: new Date(range.start),
            end: new Date(range.end)
        });
        return mergedRanges;
    }, []);
}

function stripTime(date) {
    return new Date(
        date.getFullYear(),
        date.getMonth(),
        date.getDate(),
        0,
        0,
        0,
        0
    );
}

function getMinutesSinceMidnight(date) {
    return (date.getHours() * 60) + date.getMinutes();
}

function createDateAtMinutes(date, totalMinutes) {
    return new Date(
        date.getFullYear(),
        date.getMonth(),
        date.getDate(),
        Math.floor(totalMinutes / 60),
        totalMinutes % 60,
        0,
        0
    );
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

function formatSelectedRanges(ranges) {
    if (ranges.length === 1) {
        return formatSelectedRange(ranges[0].start, ranges[0].end);
    }

    const firstRange = formatSelectedRange(ranges[0].start, ranges[0].end);
    const remainingCount = ranges.length - 1;

    return `${ranges.length} time blocks selected: ${firstRange}${remainingCount > 0 ? ` + ${remainingCount} more` : ""}`;
}

function formatSlotRangeLabel(start, end) {
    return `${formatTimeLabel(start.getHours(), start.getMinutes())} to ${formatTimeLabel(end.getHours(), end.getMinutes())}`;
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

function isSameDay(firstDate, secondDate) {
    return firstDate.getFullYear() === secondDate.getFullYear() &&
        firstDate.getMonth() === secondDate.getMonth() &&
        firstDate.getDate() === secondDate.getDate();
}
