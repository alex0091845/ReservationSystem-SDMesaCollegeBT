import {
    convertHourLabelTo24,
    formatDateKey,
    formatShortDateRange,
    getEventsForDay,
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

        timeLabel.textContent = hourLabel;

        row.appendChild(timeLabel);

        // Creating blocks for each day
        weekDates.forEach(dateObj => {
            const cell = document.createElement("div");

            cell.classList.add("week-cell");

            const currentDateKey = formatDateKey(
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

            const cellHour =
                convertHourLabelTo24(hourLabel);

            // Finds events beginning during this hour
            const matchingEvents = events.filter(event => {
                const startDate = new Date(event.start_time);

                return startDate.getHours() === cellHour;
            });

            // Calculating blocks for each event
            matchingEvents.forEach(event => {
                const eventEl = createWeekEventCard({
                    event,
                    onClick: openEventModal
                });

                // Parses timestamps
                const startDate = new Date(event.start_time);
                const endDate = new Date(event.end_time);

                const startMinutes =
                    (startDate.getHours() * 60) +
                    startDate.getMinutes();

                const endMinutes =
                    (endDate.getHours() * 60) +
                    endDate.getMinutes();

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

    centerSelectedDateColumn(weekViewWrapper);
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
