import { getDensityClass, monthNames } from "../utils/dateUtils.js";

export function renderCalendar({
    datesContainer,
    calendarTitle,
    currentYear,
    currentMonth,
    selectedDate,
    onSelectDate
}) {
    // Clears render
    datesContainer.innerHTML = "";
    calendarTitle.textContent = `${monthNames[currentMonth]} ${currentYear}`;

    // Calculates dates in month
    const firstDayIndex = new Date(currentYear, currentMonth, 1).getDay();
    const daysInMonth = new Date(currentYear, currentMonth + 1, 0).getDate();
    const totalVisibleCells = firstDayIndex + daysInMonth;
    const rowCount = Math.ceil(totalVisibleCells / 7);
    const trailingEmptyCells = (rowCount * 7) - totalVisibleCells;

    datesContainer.style.gridTemplateRows = `repeat(${rowCount}, minmax(0, 1fr))`;

    // Renders empty spaces before first day
    for (let i = 0; i < firstDayIndex; i++) {
        const emptyDiv = document.createElement("div");
        emptyDiv.classList.add("date", "other-month");
        datesContainer.appendChild(emptyDiv);
    }

    // Renders days of the selected month
    for (let day = 1; day <= daysInMonth; day++) {
        const dateDiv = document.createElement("div");

        // Selects color of the day based on event density
        dateDiv.classList.add("date", getDensityClass(currentYear, currentMonth, day));
        dateDiv.textContent = day;

        // Changes selected date on click
        const isSelected =
            selectedDate.getFullYear() === currentYear &&
            selectedDate.getMonth() === currentMonth &&
            selectedDate.getDate() === day;

        if (isSelected) {
            dateDiv.classList.add("selected");
        }

        dateDiv.addEventListener("click", () => {
            onSelectDate(new Date(currentYear, currentMonth, day));
        });

        datesContainer.appendChild(dateDiv);
    }

    // Renders empty spaces after last day
    for (let i = 0; i < trailingEmptyCells; i++) {
        const emptyDiv = document.createElement("div");
        emptyDiv.classList.add("date", "other-month");
        datesContainer.appendChild(emptyDiv);
    }
}