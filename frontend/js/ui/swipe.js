// Sideways swipes on a phone, for the Day view: swiping the schedule changes the day and
// swiping the week strip changes the week. Only a finger or a pen swipes; a mouse never does.
// Every swipe has buttons that do the same thing (the arrows beside the Day title and the
// days in the strip), so nobody has to swipe (WCAG 2.5.1 Pointer Gestures).

// Same breakpoint as the phone layout.
const phoneQuery = window.matchMedia("(max-width: 720px)");
const reducedMotionQuery = window.matchMedia("(prefers-reduced-motion: reduce)");

// A finger has to move this far before we decide whether it is swiping or scrolling.
const DECIDE_DISTANCE = 8;
// Lifting the finger after this far changes the day (or week)...
const SWIPE_DISTANCE = 70;
// ...and so does a quick flick that is shorter but at least this far and this fast (px per ms).
const FLICK_DISTANCE = 30;
const FLICK_SPEED = 0.5;
// Matches the transition in zoom-calendar.css, plus a little spare.
const SLIDE_MS = 200;

// Set when a swipe changes the day. The redraw makes a new schedule and strip, and the new
// one with the same name slides in from the side the finger was moving away from.
const pendingEntrances = {};

export function isPhoneWidth() {
    return phoneQuery.matches;
}

// Makes an element swipeable. onSwipe(1) means forward (finger moved left), onSwipe(-1) back.
// The name ties an element to the one that replaces it after the redraw.
export function addSwipe(element, { name, onSwipe }) {
    let drag = null;

    element.classList.add("is-swipeable");
    playEntrance(element, name);

    element.addEventListener("pointerdown", event => {
        if (event.pointerType === "mouse" || !event.isPrimary || !phoneQuery.matches) {
            return;
        }

        // Ignore a new touch while the last swipe is still sliding away.
        if (element.classList.contains("is-swipe-settling")) {
            return;
        }

        drag = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startY: event.clientY,
            startTime: event.timeStamp,
            isSideways: false
        };
    });

    element.addEventListener("pointermove", event => {
        if (!drag || event.pointerId !== drag.pointerId) {
            return;
        }

        const dx = event.clientX - drag.startX;
        const dy = event.clientY - drag.startY;

        if (!drag.isSideways) {
            if (Math.abs(dx) < DECIDE_DISTANCE && Math.abs(dy) < DECIDE_DISTANCE) {
                return;
            }

            // Mostly up or down: the page scrolls as usual and this touch is not a swipe.
            if (Math.abs(dy) >= Math.abs(dx)) {
                drag = null;
                return;
            }

            drag.isSideways = true;
            element.classList.add("is-swiping");

            // Keeps the moves coming to this element even if the finger leaves it.
            try {
                element.setPointerCapture(event.pointerId);
            } catch (error) {
                // Not every browser allows it here; the swipe still works without it.
            }
        }

        // The content follows the finger.
        setOffset(element, dx);
    });

    element.addEventListener("pointerup", event => finishDrag(event, true));
    element.addEventListener("pointercancel", event => finishDrag(event, false));

    function finishDrag(event, canChange) {
        if (!drag || event.pointerId !== drag.pointerId) {
            return;
        }

        const { isSideways, startX, startTime } = drag;
        const dx = event.clientX - startX;
        const speed = Math.abs(dx) / Math.max(1, event.timeStamp - startTime);

        drag = null;

        if (!isSideways) {
            return;
        }

        element.classList.remove("is-swiping");
        ignoreNextClick(element);

        const isSwipe = canChange && (
            Math.abs(dx) >= SWIPE_DISTANCE ||
            (Math.abs(dx) >= FLICK_DISTANCE && speed >= FLICK_SPEED)
        );

        if (!isSwipe) {
            // Not far enough: slide back to where it was.
            settle(element, 0, () => {});
            return;
        }

        const step = dx < 0 ? 1 : -1;
        const changeDay = () => {
            pendingEntrances[name] = step;
            onSwipe(step);
            // The redraw above has already happened (it runs straight through), so a value
            // still here means nothing replaced the element. Drop it so it can't leak into a later draw.
            delete pendingEntrances[name];
        };

        if (reducedMotionQuery.matches) {
            changeDay();
            return;
        }

        // Finish the slide off the edge, then change the day.
        settle(element, -step * element.offsetWidth, changeDay);
    }
}

function setOffset(element, offset) {
    element.style.setProperty("--swipe-offset", `${offset}px`);
}

// Animates to an offset (no animation for reduced motion), then calls done.
function settle(element, offset, done) {
    if (reducedMotionQuery.matches) {
        clearSwipe(element);
        done();
        return;
    }

    element.classList.add("is-swipe-settling");
    setOffset(element, offset);

    // A timer rather than transitionend, which does not fire if nothing actually moved.
    window.setTimeout(() => {
        clearSwipe(element);
        done();
    }, SLIDE_MS);
}

function clearSwipe(element) {
    element.classList.remove("is-swiping", "is-swipe-settling");
    element.style.removeProperty("--swipe-offset");
}

// A finger that slid across an event card or a strip day should not also tap it.
function ignoreNextClick(element) {
    const swallow = event => {
        event.preventDefault();
        event.stopPropagation();
    };

    element.addEventListener("click", swallow, { capture: true, once: true });
    window.setTimeout(() => element.removeEventListener("click", swallow, { capture: true }), 400);
}

function playEntrance(element, name) {
    const step = pendingEntrances[name];

    if (!step) {
        return;
    }

    delete pendingEntrances[name];

    const entranceClass = step > 0 ? "swipe-enter-from-right" : "swipe-enter-from-left";

    element.classList.add(entranceClass);
    element.addEventListener("animationend", () => element.classList.remove(entranceClass), { once: true });
}
