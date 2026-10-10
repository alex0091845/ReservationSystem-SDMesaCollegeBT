import { monthNames, weekdayNames } from "../utils/dateUtils.js";
import { renderDayView } from "./dayView.js";
import { renderMonthStage } from "./monthStage.js";
import { renderYearView } from "./yearView.js";

// Zoom levels the big calendar panel can show, from farthest out to closest in.
const ZOOM_LEVELS = [
    { id: "year", label: "Year" },
    { id: "month", label: "Month" },
    { id: "week", label: "Week" },
    { id: "day", label: "Day" }
];

// The latest "go up one level" action, kept so the Esc key can use it.
// It is null at the top level (Year). The zoom level itself still lives in main.js.
let zoomOut = null;
let isEscapeBound = false;

// Remembered only so a change of level can be animated: which level was showing last,
// and where in the panel the last click landed (the zoom grows out from that spot).
let lastZoomLevel = null;
let lastPointer = null;
let isPointerBound = false;

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
    onChangeMonth,
    onSelectWeek,
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
    const monthStage = getOrCreateChild(weekView, "month-stage", "append");
    const dayStage = getOrCreateChild(weekView, "day-stage", "append");

    // CSS in zoom-calendar.css hides the week header and grid while another level is showing.
    weekView.dataset.zoomLevel = zoomLevel;
    renderNav(nav, { zoomLevel, year, selectedDate, onSetZoomLevel });
    renderToolbar(toolbar, zoomLevel, onSetZoomLevel);
    bindEscapeOnce();

    yearStage.hidden = zoomLevel !== "year";
    monthStage.hidden = zoomLevel !== "month";
    dayStage.hidden = zoomLevel !== "day";
    yearStage.innerHTML = "";
    monthStage.innerHTML = "";
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

    if (zoomLevel === "month") {
        renderMonthStage({
            container: monthStage,
            selectedDate,
            reservedEvents,
            onChangeMonth,
            onSelectWeek,
            openEventModal
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

    bindPointerOnce(weekView);
    playZoomAnimation(weekView, zoomLevel);
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

// The words shown in the breadcrumb for one level, e.g. "2026", "October", "Week of Oct 4", "Fri, Oct 9".
function getCrumbLabel(levelId, { zoomLevel, year, selectedDate }) {
    if (levelId === "year") {
        // In the Year view the arrows can move to a year other than the selected date's.
        return String(zoomLevel === "year" ? year : selectedDate.getFullYear());
    }

    if (levelId === "month") {
        return monthNames[selectedDate.getMonth()];
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

// Remembers where the last click inside the panel landed. Clicks on the bar at the top
// (Back, breadcrumb, zoom buttons) are ignored, so those zoom from the middle instead.
function bindPointerOnce(weekView) {
    if (isPointerBound) {
        return;
    }

    isPointerBound = true;

    weekView.addEventListener("pointerdown", event => {
        const isOnBar = event.target instanceof Element && event.target.closest(".zoom-bar");

        lastPointer = isOnBar ? null : { x: event.clientX, y: event.clientY };
    });
}

// Plays a short scale-and-fade when the zoom level has changed since the last draw.
// Zooming in grows out from the spot that was clicked; zooming out settles back from slightly larger.
function playZoomAnimation(weekView, zoomLevel) {
    const previousLevel = lastZoomLevel;
    const pointer = lastPointer;

    lastZoomLevel = zoomLevel;
    lastPointer = null;

    if (previousLevel === null || previousLevel === zoomLevel) {
        return;
    }

    // People who ask their device for less motion get an instant switch.
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        return;
    }

    const levelIds = ZOOM_LEVELS.map(level => level.id);
    const isZoomingIn = levelIds.indexOf(zoomLevel) > levelIds.indexOf(previousLevel);
    const animationClass = isZoomingIn ? "zoom-anim-in" : "zoom-anim-out";

    // Everything in the panel except the bar at the top takes part.
    Array.from(weekView.children).forEach(child => {
        if (child.classList.contains("zoom-bar") || child.offsetParent === null) {
            return;
        }

        const box = child.getBoundingClientRect();

        child.style.transformOrigin = isZoomingIn && pointer
            ? `${pointer.x - box.left}px ${pointer.y - box.top}px`
            : "50% 0";

        // Removing the class and reading a size first lets the animation restart on a quick second zoom.
        child.classList.remove("zoom-anim-in", "zoom-anim-out");
        void child.offsetWidth;
        child.classList.add(animationClass);
        child.addEventListener("animationend", () => {
            child.classList.remove(animationClass);
            child.style.transformOrigin = "";
        }, { once: true });
    });
}
