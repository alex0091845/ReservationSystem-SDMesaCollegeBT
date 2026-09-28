import {
    CALENDAR_END_LABEL,
    CALENDAR_END_HOUR,
    convertHourLabelTo24,
    formatDateKey,
    formatShortDateRange,
    getEventsForDay,
    getVisibleEventSectionForDay,
    hourNames,
    weekdayNames
} from "../utils/dateUtils.js";
import { createWeekEventCard } from "./eventCards.js";

export function renderWeekView({
    weekViewWrapper,
    weekViewTitle,
    selectedDate,
    reservedEvents,
    onSelectDate,
    openEventModal
}) {
    // Clears previous render
    weekViewWrapper.innerHTML = "";

    const selectedYear = selectedDate.getFullYear();
    const selectedMonth = selectedDate.getMonth();
    const selectedDay = selectedDate.getDate();

    // Finds Sunday of current week
    const weekStart = new Date(selectedDate);
    weekStart.setDate(
        selectedDate.getDate() - selectedDate.getDay()
    );

    // Generates all 7 dates for current week
    const weekDates = [];

    for (let i = 0; i < 7; i++) {
        const date = new Date(weekStart);

        date.setDate(weekStart.getDate() + i);

        weekDates.push(date);
    }

    // Updates week view title
    const weekEnd = weekDates[6];

    weekViewTitle.textContent = formatShortDateRange(
        weekDates[0],
        weekEnd
    );

    const header = document.createElement("div");

    header.classList.add("week-header");

    // Empty top-left corner
    const corner = document.createElement("div");

    corner.classList.add("week-corner");

    header.appendChild(corner);

    // Creates day labels
    weekDates.forEach(dateObj => {
        const headerCell = document.createElement("div");

        headerCell.classList.add("week-day-header");
        headerCell.dataset.weekDate = formatDateKey(
            dateObj.getFullYear(),
            dateObj.getMonth(),
            dateObj.getDate()
        );

        const isSelected =
            dateObj.getFullYear() === selectedYear &&
            dateObj.getMonth() === selectedMonth &&
            dateObj.getDate() === selectedDay;

        if (isSelected) {
            headerCell.classList.add("current-day-column");
        }

        headerCell.innerHTML = `
            <div class="week-day-name">
                ${weekdayNames[dateObj.getDay()]}
            </div>

            <div class="week-day-number">
                ${dateObj.getDate()}
            </div>
        `;

        // Makes day header clickable
        headerCell.addEventListener("click", () => {
            onSelectDate(
                new Date(
                    dateObj.getFullYear(),
                    dateObj.getMonth(),
                    dateObj.getDate()
                )
            );
        });

        header.appendChild(headerCell);
    });

    weekViewWrapper.appendChild(header);

    const hourRowHeight = getWeekHourRowHeight(weekViewWrapper);
    const minEventHeight = getWeekMinEventHeight(weekViewWrapper);

    // Hour Labels
    hourNames.forEach((hourLabel, rowIndex) => {
        const row = document.createElement("div");

        row.classList.add("week-row");

        // Keeps earlier rows above later rows
        row.style.zIndex = String(
            hourNames.length - rowIndex
        );

        // Static Labels
        const timeLabel = document.createElement("div");

        timeLabel.classList.add("week-time-label");

        const timeLabelText = document.createElement("span");
        timeLabelText.classList.add("week-time-label-text");
        timeLabelText.textContent = hourLabel;
        timeLabel.appendChild(timeLabelText);

        row.appendChild(timeLabel);
        const cellHour = convertHourLabelTo24(hourLabel);
        row.dataset.hour = String(cellHour);

        // Creating blocks for each day
        weekDates.forEach(dateObj => {
            const cell = document.createElement("div");

            cell.classList.add("week-cell");
            cell.dataset.weekDate = formatDateKey(
                dateObj.getFullYear(),
                dateObj.getMonth(),
                dateObj.getDate()
            );

            // Allows clicking any cell to select day
            cell.addEventListener("click", () => {
                onSelectDate(
                    new Date(
                        dateObj.getFullYear(),
                        dateObj.getMonth(),
                        dateObj.getDate()
                    )
                );
            });

            // Highlights selected column
            const isSelected =
                dateObj.getFullYear() === selectedYear &&
                dateObj.getMonth() === selectedMonth &&
                dateObj.getDate() === selectedDay;

            if (isSelected) {
                cell.classList.add("current-day-column");
            }

            // Gets events for this day
            const events = getEventsForDay(
                reservedEvents,
                dateObj.getFullYear(),
                dateObj.getMonth(),
                dateObj.getDate()
            );

            // Finds visible daily sections beginning during this hour.
            const matchingEventSections = events
                .map(event => ({
                    event,
                    section: getVisibleEventSectionForDay(event, dateObj)
                }))
                .filter(({ section }) => {
                    return section && section.start.getHours() === cellHour;
                });

            // Calculating blocks for each event
            matchingEventSections.forEach(({ event, section }) => {
                const eventEl = createWeekEventCard({
                    event,
                    onClick: openEventModal
                });

                const startMinutes =
                    (section.start.getHours() * 60) +
                    section.start.getMinutes();

                const endMinutes =
                    Math.min(
                        (section.end.getHours() * 60) +
                        section.end.getMinutes(),
                        CALENDAR_END_HOUR * 60
                    );

                const rowStartMinutes =
                    cellHour * 60;

                // Cell positioning math
                const pxPerMinute =
                    hourRowHeight / 60;

                // Vertical offset within hour row
                const topOffset =
                    (startMinutes - rowStartMinutes) *
                    pxPerMinute;

                // Height based on duration
                const blockHeight =
                    Math.max(
                        (endMinutes - startMinutes) * pxPerMinute,
                        minEventHeight
                    );

                eventEl.style.top =
                    `${topOffset}px`;

                eventEl.style.height =
                    `${blockHeight}px`;

                cell.appendChild(eventEl);
            });

            row.appendChild(cell);
        });

        weekViewWrapper.appendChild(row);
    });

    weekViewWrapper.appendChild(createEndBoundaryRow({
        weekDates,
        selectedYear,
        selectedMonth,
        selectedDay,
        onSelectDate
    }));

    updateCurrentTimeIndicator(weekViewWrapper);
    centerSelectedDateColumn(weekViewWrapper);
}

export function updateCurrentTimeIndicator(weekViewWrapper) {
    if (!weekViewWrapper) {
        return;
    }

    weekViewWrapper.querySelectorAll(".week-current-time-indicator").forEach(indicator => {
        indicator.remove();
    });

    const now = new Date();
    const minutesSinceMidnight = (now.getHours() * 60) + now.getMinutes();
    const calendarStartMinutes = CALENDAR_START_HOUR * 60;
    const calendarEndMinutes = CALENDAR_END_HOUR * 60;

    if (minutesSinceMidnight < calendarStartMinutes || minutesSinceMidnight >= calendarEndMinutes) {
        return;
    }

    const dateKey = formatDateKey(now.getFullYear(), now.getMonth(), now.getDate());
    const currentHourRow = weekViewWrapper.querySelector(
        `.week-row[data-hour="${now.getHours()}"]`
    );
    const currentDayCell = currentHourRow?.querySelector(
        `.week-cell[data-week-date="${dateKey}"]`
    );

    if (!currentDayCell) {
        return;
    }

    const indicator = document.createElement("div");
    const minuteOfHour = now.getMinutes() + (now.getSeconds() / 60);
    const rowHeight = currentDayCell.getBoundingClientRect().height;

    indicator.className = "week-current-time-indicator";
    indicator.setAttribute("aria-hidden", "true");
    indicator.style.top = `${(minuteOfHour / 60) * rowHeight}px`;
    currentDayCell.appendChild(indicator);
}

function createEndBoundaryRow({
    weekDates,
    selectedYear,
    selectedMonth,
    selectedDay,
    onSelectDate
}) {
    const row = document.createElement("div");
    const timeLabel = document.createElement("div");
    const timeLabelText = document.createElement("span");

    row.classList.add("week-row", "week-end-boundary-row");
    timeLabel.classList.add("week-time-label");
    timeLabelText.classList.add("week-time-label-text");
    timeLabelText.textContent = CALENDAR_END_LABEL;
    timeLabel.appendChild(timeLabelText);
    row.appendChild(timeLabel);

    weekDates.forEach(dateObj => {
        const cell = document.createElement("div");
        const isSelected =
            dateObj.getFullYear() === selectedYear &&
            dateObj.getMonth() === selectedMonth &&
            dateObj.getDate() === selectedDay;

        cell.classList.add("week-cell");
        cell.dataset.weekDate = formatDateKey(
            dateObj.getFullYear(),
            dateObj.getMonth(),
            dateObj.getDate()
        );

        if (isSelected) {
            cell.classList.add("current-day-column");
        }

        cell.addEventListener("click", () => {
            onSelectDate(
                new Date(
                    dateObj.getFullYear(),
                    dateObj.getMonth(),
                    dateObj.getDate()
                )
            );
        });

        row.appendChild(cell);
    });

    return row;
}

function getWeekHourRowHeight(weekViewWrapper) {
    const rowHeight = parseFloat(
        getComputedStyle(weekViewWrapper)
            .getPropertyValue("--week-hour-row-height")
    );

    return Number.isFinite(rowHeight) ? rowHeight : 68;
}

function getWeekMinEventHeight(weekViewWrapper) {
    const minEventHeight = parseFloat(
        getComputedStyle(weekViewWrapper)
            .getPropertyValue("--week-min-event-height")
    );

    return Number.isFinite(minEventHeight) ? minEventHeight : 24;
}

function centerSelectedDateColumn(weekViewWrapper) {
    const selectedHeader = weekViewWrapper.querySelector(
        ".week-day-header.current-day-column"
    );

    if (!selectedHeader) {
        return;
    }

    requestAnimationFrame(() => {
        const wrapperRect =
            weekViewWrapper.getBoundingClientRect();

        const selectedRect =
            selectedHeader.getBoundingClientRect();

        const targetScrollLeft =
            weekViewWrapper.scrollLeft +
            selectedRect.left -
            wrapperRect.left +
            (selectedRect.width / 2) -
            (wrapperRect.width / 2);

        weekViewWrapper.scrollLeft = Math.max(0, targetScrollLeft);
    });
}
