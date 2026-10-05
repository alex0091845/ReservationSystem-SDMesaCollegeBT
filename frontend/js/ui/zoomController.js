import { renderDayView } from "./dayView.js";
import { renderYearView } from "./yearView.js";

// Zoom levels the big calendar panel can show. Month comes in a later step.
const ZOOM_LEVELS = [
    { id: "year", label: "Year" },
    { id: "week", label: "Week" },
    { id: "day", label: "Day" }
];

// Draws the zoom buttons and shows the panel for the current zoom level.
// State lives in main.js; this module only renders.
export function renderZoomStage({
    weekView,
    zoomLevel,
    year,
    selectedDate,
    reservedEvents,
    onSetZoomLevel,
    onShowYear,
    onSelectMonth,
    onSelectDate,
    openEventModal
}) {
    if (!weekView) {
        return;
    }

    const toolbar = getOrCreateChild(weekView, "zoom-toolbar", "prepend");
    const yearStage = getOrCreateChild(weekView, "year-stage", "append");
    const dayStage = getOrCreateChild(weekView, "day-stage", "append");

    // CSS in zoom-calendar.css hides the week header and grid while another level is showing.
    weekView.dataset.zoomLevel = zoomLevel;
    renderToolbar(toolbar, zoomLevel, onSetZoomLevel);

    yearStage.hidden = zoomLevel !== "year";
    dayStage.hidden = zoomLevel !== "day";
    yearStage.innerHTML = "";
    dayStage.innerHTML = "";

    if (zoomLevel === "year") {
        renderYearView({
            container: yearStage,
            year,
            selectedDate,
            reservedEvents,
            onShowYear,
            onSelectMonth
        });
    }

    if (zoomLevel === "day") {
        renderDayView({
            container: dayStage,
            selectedDate,
            reservedEvents,
            onSelectDate,
            openEventModal
        });
    }
}

function getOrCreateChild(parent, className, position) {
    const existing = parent.querySelector(`:scope > .${className}`);

    if (existing) {
        return existing;
    }

    const child = document.createElement("div");

    child.classList.add(className);
    parent[position](child);

    return child;
}

function renderToolbar(toolbar, zoomLevel, onSetZoomLevel) {
    toolbar.innerHTML = "";
    toolbar.setAttribute("role", "group");
    toolbar.setAttribute("aria-label", "Calendar zoom level");

    ZOOM_LEVELS.forEach(level => {
        const button = document.createElement("button");

        button.type = "button";
        button.classList.add("zoom-level-btn");
        button.textContent = level.label;
        button.setAttribute("aria-pressed", String(level.id === zoomLevel));
        button.addEventListener("click", () => onSetZoomLevel(level.id));
        toolbar.appendChild(button);
    });
}
