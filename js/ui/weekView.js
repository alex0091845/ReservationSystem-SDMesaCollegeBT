import {
    convertHourLabelTo24,
    formatDateKey,
    formatShortDateRange,
    getEventsForDay,
    hourNames,
    parseTime,
    weekdayNames
} from "../utils/dateUtils.js";

export function renderWeekView({
    weekViewWrapper,
    weekViewTitle,
    selectedDate,
    reservedEvents,
    onSelectDate,
    openEventModal
}) {
    // Clears render
    weekViewWrapper.innerHTML = "";

    const selectedYear = selectedDate.getFullYear();
    const selectedMonth = selectedDate.getMonth();
    const selectedDay = selectedDate.getDate();

    // Finds Sunday of that week
    const weekStart = new Date(selectedDate);
    weekStart.setDate(selectedDate.getDate() - selectedDate.getDay());

    // Assigns dates to each day of the week
    const weekDates = [];
    for (let i = 0; i < 7; i++) {
        const d = new Date(weekStart);
        d.setDate(weekStart.getDate() + i);
        weekDates.push(d);
    }

    // Generates week date range
    const weekEnd = weekDates[6];
    weekViewTitle.textContent = formatShortDateRange(weekDates[0], weekEnd);

    // Header
    const header = document.createElement("div");
    header.classList.add("week-header");

    // Top left blank cell in grid
    const corner = document.createElement("div");
    corner.classList.add("week-corner");
    header.appendChild(corner);

    weekDates.forEach(dateObj => {
        const headerCell = document.createElement("div");
        headerCell.classList.add("week-day-header");

        // Highlights selected date
        const isSelected =
            dateObj.getFullYear() === selectedYear &&
            dateObj.getMonth() === selectedMonth &&
            dateObj.getDate() === selectedDay;

        if (isSelected) {
            headerCell.classList.add("current-day-column");
        }

        // Adds day of week and dates to top row cells of grid
        headerCell.innerHTML = `
            <div class="week-day-name">${weekdayNames[dateObj.getDay()]}</div>
            <div class="week-day-number">${dateObj.getDate()}</div>
        `;

        // Makes header cells clickable
        headerCell.addEventListener("click", () => {
            onSelectDate(new Date(
                dateObj.getFullYear(),
                dateObj.getMonth(),
                dateObj.getDate()
            ));
        });

        header.appendChild(headerCell);
    });

    weekViewWrapper.appendChild(header);

    // Creates a row for each hour from 8AM to 8PM
    hourNames.forEach((hourLabel, rowIndex) => {
        const row = document.createElement("div");
        row.classList.add("week-row");

        // Earlier rows should sit above later rows so tall events remain clickable
        row.style.zIndex = String(hourNames.length - rowIndex);

        // Adds the hour label to the left column
        const timeLabel = document.createElement("div");
        timeLabel.classList.add("week-time-label");
        timeLabel.textContent = hourLabel;
        row.appendChild(timeLabel);

        // Adds a cell for each day of the week
        weekDates.forEach(dateObj => {
            const cell = document.createElement("div");
            cell.classList.add("week-cell");

            const currentDateKey = formatDateKey(
                dateObj.getFullYear(),
                dateObj.getMonth(),
                dateObj.getDate()
            );

            // Allows dates to be selected by clicking any hour cell within its column
            cell.addEventListener("click", () => {
                onSelectDate(new Date(
                    dateObj.getFullYear(),
                    dateObj.getMonth(),
                    dateObj.getDate()
                ));
            });

            // Highlights selected date column
            const isSelected =
                dateObj.getFullYear() === selectedYear &&
                dateObj.getMonth() === selectedMonth &&
                dateObj.getDate() === selectedDay;

            if (isSelected) {
                cell.classList.add("current-day-column");
            }

            /* Pulls events from this day. Needs to be replaced with calls to backend*/
            const events = getEventsForDay(
                reservedEvents,
                dateObj.getFullYear(),
                dateObj.getMonth(),
                dateObj.getDate()
            );

            const cellHour = convertHourLabelTo24(hourLabel);

            // Filters events to find ones starting in current hour row
            const matchingEvents = events.filter(event => {
                const start = parseTime(event.start);
                return start.hour === cellHour;
            });

            // Displays relevant events
            matchingEvents.forEach(event => {
                // Creates clickable button for modal
                const eventEl = document.createElement("button");
                eventEl.type = "button";
                eventEl.classList.add("week-event", event.color);
                eventEl.textContent = event.title;

                // Parses event time data
                const start = parseTime(event.start);
                const end = parseTime(event.end);
                const startMinutes = start.hour * 60 + start.minute;
                const endMinutes = end.hour * 60 + end.minute;
                const rowStartMinutes = cellHour * 60;

                const HOUR_ROW_HEIGHT = 68;
                const pxPerMinute = HOUR_ROW_HEIGHT / 60;
                // Calculates padding to start event block in hour row
                const topOffset = (startMinutes - rowStartMinutes) * pxPerMinute;
                const blockHeight = (endMinutes - startMinutes) * pxPerMinute;
                eventEl.style.top = `${topOffset}px`;
                eventEl.style.height = `${blockHeight}px`;

                // Opens modal for clicked event
                eventEl.addEventListener("click", clickEvent => {
                    clickEvent.stopPropagation();
                    openEventModal(event, currentDateKey);
                });

                cell.appendChild(eventEl);
            });

            row.appendChild(cell);
        });

        weekViewWrapper.appendChild(row);
    });
}