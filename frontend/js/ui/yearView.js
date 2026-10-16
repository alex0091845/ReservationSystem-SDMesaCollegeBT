import { getDensityClass, monthNames } from "../utils/dateUtils.js";

const DAY_CELLS_PER_MONTH = 42; // 6 weeks x 7 days, so every month card is the same height

// Renders the Year zoom level: 12 mini months shaded by how busy each day is.
export function renderYearView({
    container,
    year,
    selectedDate,
    reservedEvents,
    onShowYear,
    onSelectMonth
}) {
    container.innerHTML = "";

    container.appendChild(createYearHeader(year, onShowYear));

    const grid = document.createElement("div");
    grid.classList.add("year-grid");

    for (let month = 0; month < 12; month++) {
        grid.appendChild(createMonthCard({
            year,
            month,
            selectedDate,
            reservedEvents,
            onSelectMonth
        }));
    }

    container.appendChild(grid);
    container.appendChild(createDensityLegend());
}

function createYearHeader(year, onShowYear) {
    const header = document.createElement("div");
    const prevButton = createYearNavButton("←", `Show ${year - 1}`, () => onShowYear(year - 1));
    const nextButton = createYearNavButton("→", `Show ${year + 1}`, () => onShowYear(year + 1));
    const title = document.createElement("h3");

    header.classList.add("year-header");
    title.classList.add("year-title");
    title.textContent = String(year);

    header.append(prevButton, title, nextButton);

    return header;
}

function createYearNavButton(arrow, label, onClick) {
    const button = document.createElement("button");

    button.type = "button";
    button.classList.add("nav-btn");
    button.textContent = arrow;
    button.setAttribute("aria-label", label);
    button.addEventListener("click", onClick);

    return button;
}

function createMonthCard({ year, month, selectedDate, reservedEvents, onSelectMonth }) {
    const card = document.createElement("button");
    const name = document.createElement("span");
    const days = document.createElement("span");
    const today = new Date();
    const firstDayIndex = new Date(year, month, 1).getDay();
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    let busyDayCount = 0;

    card.type = "button";
    card.classList.add("year-month");
    name.classList.add("year-month-name");
    name.textContent = monthNames[month];
    days.classList.add("year-month-days");
    days.setAttribute("aria-hidden", "true");

    if (today.getFullYear() === year && today.getMonth() === month) {
        card.classList.add("is-current-month");
    }

    for (let cellIndex = 0; cellIndex < DAY_CELLS_PER_MONTH; cellIndex++) {
        const cell = document.createElement("span");
        const day = cellIndex - firstDayIndex + 1;

        cell.classList.add("year-day");

        if (day < 1 || day > daysInMonth) {
            cell.classList.add("is-empty");
            days.appendChild(cell);
            continue;
        }

        const densityClass = getDensityClass(reservedEvents, year, month, day);

        cell.classList.add(densityClass);

        if (densityClass !== "level-0") {
            busyDayCount++;
        }

        if (isSameDay(today, year, month, day)) {
            cell.classList.add("is-today");
        }

        if (isSameDay(selectedDate, year, month, day)) {
            cell.classList.add("is-selected");
        }

        days.appendChild(cell);
    }

    card.setAttribute(
        "aria-label",
        `${monthNames[month]} ${year}, ${describeBusyDays(busyDayCount)}. Zoom in.`
    );
    card.addEventListener("click", () => onSelectMonth(year, month));
    card.append(name, days);

    return card;
}

function isSameDay(dateObj, year, month, day) {
    return dateObj.getFullYear() === year &&
        dateObj.getMonth() === month &&
        dateObj.getDate() === day;
}

function describeBusyDays(count) {
    if (count === 0) return "no days with events";
    if (count === 1) return "1 day with events";

    return `${count} days with events`;
}

function createDensityLegend() {
    const legend = document.createElement("div");
    const fewer = document.createElement("span");
    const more = document.createElement("span");

    legend.classList.add("year-legend");
    fewer.textContent = "Fewer events";
    more.textContent = "More";
    legend.appendChild(fewer);

    ["level-0", "level-1", "level-2", "level-3", "level-4"].forEach(level => {
        const swatch = document.createElement("span");

        swatch.classList.add("year-legend-swatch", level);
        swatch.setAttribute("aria-hidden", "true");
        legend.appendChild(swatch);
    });

    legend.appendChild(more);

    return legend;
}
