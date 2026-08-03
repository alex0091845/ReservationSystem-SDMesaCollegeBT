export function createActionMenu({ trigger, menu }) {
    if (!trigger || !menu) {
        return {
            close() {}
        };
    }

    const container = trigger.closest(".reservation-delete-menu");

    function getAvailableItems() {
        return Array.from(menu.querySelectorAll('[role="menuitem"]')).filter(item => {
            return !item.hidden && !item.disabled;
        });
    }

    function close({ restoreFocus = false } = {}) {
        menu.hidden = true;
        trigger.setAttribute("aria-expanded", "false");

        if (restoreFocus) {
            trigger.focus();
        }
    }

    function positionMenu() {
        const triggerRect = trigger.getBoundingClientRect();
        const menuRect = menu.getBoundingClientRect();
        const viewportPadding = 8;
        const menuGap = 7;
        const left = Math.min(
            Math.max(triggerRect.left, viewportPadding),
            window.innerWidth - menuRect.width - viewportPadding
        );
        const topAbove = triggerRect.top - menuRect.height - menuGap;
        const top = topAbove >= viewportPadding
            ? topAbove
            : triggerRect.bottom + menuGap;

        menu.style.left = `${left}px`;
        menu.style.top = `${top}px`;
    }

    function open() {
        if (trigger.disabled) {
            return;
        }

        menu.hidden = false;
        trigger.setAttribute("aria-expanded", "true");
        positionMenu();
        getAvailableItems()[0]?.focus();
    }

    trigger.addEventListener("click", event => {
        event.stopPropagation();

        if (menu.hidden) {
            open();
        } else {
            close({ restoreFocus: true });
        }
    });

    menu.addEventListener("click", event => {
        if (event.target.closest('[role="menuitem"]')) {
            close();
        }
    }, true);

    menu.addEventListener("keydown", event => {
        const items = getAvailableItems();
        const currentIndex = items.indexOf(document.activeElement);

        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            if (items.length === 0) {
                return;
            }

            event.preventDefault();
            const direction = event.key === "ArrowDown" ? 1 : -1;
            const nextIndex = (currentIndex + direction + items.length) % items.length;
            items[nextIndex]?.focus();
        }
    });

    document.addEventListener("click", event => {
        if (!menu.hidden && !container?.contains(event.target)) {
            close();
        }
    });

    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && !menu.hidden) {
            event.preventDefault();
            event.stopImmediatePropagation();
            close({ restoreFocus: true });
        }
    }, true);

    window.addEventListener("resize", close);
    document.addEventListener("scroll", () => {
        if (!menu.hidden) {
            positionMenu();
        }
    }, true);

    return { close };
}
