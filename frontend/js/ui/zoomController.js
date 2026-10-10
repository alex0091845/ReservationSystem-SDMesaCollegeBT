import { monthNames, weekdayNames } from "../utils/dateUtils.js";
import { renderDayView } from "./dayView.js";
import { renderYearView } from "./yearView.js";

// Zoom levels the big calendar panel can show, from farthest out to closest in.
// Month comes in a later step.
const ZOOM_LEVELS = [
    { id: "year", label: "Year" },
    { id: "week", label: "Week" },
    { id: "day", label: "Day" }
];

// The latest "go up one level" action, kept so the Esc key can use it.
// It is null at the top level (Year). The zoom level itself still lives in main.js.
let zoomOut = null;
let isEscapeBound = false;

// Draws the Back button, the breadcrumb and the zoom buttons, and shows the panel
// for the current zoom level. State lives in main.js; this module only renders.
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

    const bar = getOrCreateChild(weekView, "zoom-bar", "prepend");
    const nav = getOrCreateChild(bar, "zoom-nav", "append");
    const toolbar = getOrCreateChild(bar, "zoom-toolbar", "append");
    const yearStage = getOrCreateChild(weekView, "year-stage", "append");
    const dayStage = getOrCreateChild(weekView, "day-stage", "append");

    // CSS in zoom-calendar.css hides the week header and grid while another level is showing.
    weekView.dataset.zoomLevel = zoomLevel;
    renderNav(nav, { zoomLevel, year, selectedDate, onSetZoomLevel });
    renderToolbar(toolbar, zoomLevel, onSetZoomLevel);
    bindEscapeOnce();

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

// The words shown in the breadcrumb for one level, e.g. "2026", "Week of Oct 4", "Fri, Oct 9".
function getCrumbLabel(levelId, { zoomLevel, year, selectedDate }) {
    if (levelId === "year") {
        // In the Year view the arrows can move to a year other than the selected date's.
        return String(zoomLevel === "year" ? year : selectedDate.getFullYear());
    }

    if (levelId === "week") {
        const weekStart = new Date(selectedDate);

        weekStart.setDate(selectedDate.getDate() - selectedDate.getDay());

        return `Week of ${formatShortDate(weekStart)}`;
    }

    return `${weekdayNames[selectedDate.getDay()]}, ${formatShortDate(selectedDate)}`;
}

function formatShortDate(dateObj) {
    return `${monthNames[dateObj.getMonth()].slice(0, 3)} ${dateObj.getDate()}`;
}

// Back button plus breadcrumb. The breadcrumb lists every level from Year down to
// the one showing; the levels above the current one are buttons that zoom out to them.
function renderNav(nav, { zoomLevel, year, selectedDate, onSetZoomLevel }) {
    const currentIndex = ZOOM_LEVELS.findIndex(level => level.id === zoomLevel);
    const parentLevel = ZOOM_LEVELS[currentIndex - 1] || null;
    const labelContext = { zoomLevel, year, selectedDate };
    const hadFocus = nav.contains(document.activeElement);

    zoomOut = parentLevel ? () => onSetZoomLevel(parentLevel.id) : null;
    nav.innerHTML = "";

    const backButton = document.createElement("button");

    backButton.type = "button";
    backButton.classList.add("zoom-back-btn");
    backButton.textContent = "← Back";
    backButton.disabled = !parentLevel;

    if (parentLevel) {
        backButton.title = "Back (Esc)";
        backButton.setAttribute(
            "aria-label",
            `Back to ${getCrumbLabel(parentLevel.id, labelContext)}`
        );
        backButton.addEventListener("click", zoomOut);
    }

    const breadcrumb = document.createElement("nav");

    breadcrumb.classList.add("zoom-breadcrumb");
    breadcrumb.setAttribute("aria-label", "Calendar location");

    ZOOM_LEVELS.slice(0, currentIndex + 1).forEach((level, index) => {
        if (index > 0) {
            const separator = document.createElement("span");

            separator.classList.add("zoom-crumb-separator");
            separator.setAttribute("aria-hidden", "true");
            separator.textContent = "›";
            breadcrumb.appendChild(separator);
        }

        const label = getCrumbLabel(level.id, labelContext);

        if (index === currentIndex) {
            const current = document.createElement("span");

            current.classList.add("zoom-crumb-current");
            current.setAttribute("aria-current", "location");
            current.textContent = label;
            breadcrumb.appendChild(current);

            return;
        }

        const crumb = document.createElement("button");

        crumb.type = "button";
        crumb.classList.add("zoom-crumb");
        crumb.textContent = label;
        crumb.addEventListener("click", () => onSetZoomLevel(level.id));
        breadcrumb.appendChild(crumb);
    });

    nav.append(backButton, breadcrumb);

    // Redrawing replaces the buttons, so hand keyboard focus back to the new Back button.
    if (hadFocus && parentLevel) {
        backButton.focus();
    }
}

// Esc zooms out one level. Bound once; it always uses the latest zoomOut action.
function bindEscapeOnce() {
    if (isEscapeBound) {
        return;
    }

    isEscapeBound = true;

    // "true" makes this run before main.js's own Esc handler, so an open event
    // window is still open when we check for it and Esc closes only that window.
    document.addEventListener(
        "keydown",
        event => {
            if (event.key !== "Escape" || !zoomOut) {
                return;
            }

            if (document.querySelector(".modal-overlay.active")) {
                return;
            }

            if (event.target instanceof Element && event.target.closest("input, textarea, select")) {
                return;
            }

            zoomOut();
        },
        true
    );
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
