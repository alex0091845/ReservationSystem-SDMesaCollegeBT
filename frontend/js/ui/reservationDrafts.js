import { deleteReservationDraft, getReservationDraft, saveReservationDraft } from "../api.js";

const AUTOSAVE_DELAY_MS = 700;

export function createReservationDraftAutosave({
    form,
    timePicker,
    draftType,
    getSourceEventId = () => null,
    getHostUserId = () => null,
    collectPayload,
    applyPayload,
    badgeElement = null,
    saveButton = null,
    onSaveSuccess = () => {},
    onSaveError = () => {}
}) {
    let active = false;
    let currentDraft = null;
    let isApplyingDraft = false;
    let baselinePayload = "";
    let lastSavedPayload = "";
    let saveTimer = null;
    let pendingSavePromise = null;
    let saveQueued = false;
    let saveButtonResetTimer = null;

    if (form) {
        form.addEventListener("input", scheduleSave);
        form.addEventListener("change", scheduleSave);
    }

    if (saveButton) {
        saveButton.addEventListener("click", async () => {
            try {
                const savedDraft = await saveNow({ force: true });

                if (savedDraft) {
                    markClean();
                    showDraftBadge(true);
                    setSaveButtonState("saved");
                    onSaveSuccess(savedDraft);
                }
            } catch (error) {
                setSaveButtonState("error");
                onSaveError(error);
            }
        });
    }

    async function activate() {
        active = true;
        clearSaveTimer();
        showDraftBadge(false);

        try {
            currentDraft = await getReservationDraft({
                draft_type: draftType,
                source_event_id: getSourceEventId()
            });
        } catch (error) {
            console.error("Could not load reservation draft:", error);
            currentDraft = null;
        }

        if (currentDraft?.payload && applyPayload) {
            isApplyingDraft = true;

            try {
                applyPayload(currentDraft.payload);
            } finally {
                isApplyingDraft = false;
            }
        }

        baselinePayload = serializePayload(collectPayload());
        lastSavedPayload = baselinePayload;
        showDraftBadge(Boolean(currentDraft?.id));

        return currentDraft;
    }

    function deactivate({ flush = false } = {}) {
        if (flush && saveTimer) {
            saveNow();
        } else {
            clearSaveTimer();
        }

        active = false;
        saveQueued = false;
    }

    async function discard() {
        clearSaveTimer();
        saveQueued = false;

        if (pendingSavePromise) {
            try {
                await pendingSavePromise;
            } catch (error) {
                // The save failure was already logged in saveNow; still clear local draft state.
            }
        }

        if (!currentDraft?.id) {
            currentDraft = null;
            lastSavedPayload = "";
            baselinePayload = serializePayload(collectPayload());
            showDraftBadge(false);
            return;
        }

        try {
            await deleteReservationDraft(currentDraft);
        } catch (error) {
            console.error("Could not delete reservation draft:", error);
        } finally {
            currentDraft = null;
            lastSavedPayload = "";
            baselinePayload = serializePayload(collectPayload());
            showDraftBadge(false);
        }
    }

    function hasChanges() {
        if (!active || isApplyingDraft) {
            return false;
        }

        const payload = collectPayload();

        return hasDraftContent(payload) &&
            serializePayload(payload) !== baselinePayload;
    }

    function scheduleSave() {
        if (!active || isApplyingDraft) {
            return;
        }

        clearSaveTimer();
        saveTimer = window.setTimeout(saveNow, AUTOSAVE_DELAY_MS);
    }

    async function saveNow({ force = false } = {}) {
        clearSaveTimer();

        if (!active || isApplyingDraft) {
            return null;
        }

        if (pendingSavePromise) {
            const inFlightSave = pendingSavePromise;
            saveQueued = true;

            try {
                await inFlightSave;
            } catch (error) {
                if (!force) {
                    throw error;
                }
            }

            if (!active || isApplyingDraft) {
                return currentDraft;
            }

            return saveNow({ force });
        }

        const payload = collectPayload();

        if (!force && !hasDraftContent(payload)) {
            return null;
        }

        const serializedPayload = serializePayload(payload);

        if (!force && serializedPayload === lastSavedPayload) {
            return currentDraft;
        }

        try {
            setSaveButtonState("saving");
            pendingSavePromise = saveReservationDraft({
                id: currentDraft?.id,
                draft_type: draftType,
                source_event_id: getSourceEventId(),
                host_user_id: getHostUserId(),
                payload
            });
            currentDraft = await pendingSavePromise;
            lastSavedPayload = serializedPayload;
            showDraftBadge(Boolean(currentDraft?.id));

            return currentDraft;
        } catch (error) {
            console.error("Could not save reservation draft:", error);
            throw error;
        } finally {
            pendingSavePromise = null;

            if (saveQueued && active) {
                saveQueued = false;
                scheduleSave();
            }

            setSaveButtonState("idle");
        }
    }

    function clearSaveTimer() {
        if (saveTimer) {
            window.clearTimeout(saveTimer);
            saveTimer = null;
        }
    }

    function markClean() {
        baselinePayload = serializePayload(collectPayload());
        lastSavedPayload = baselinePayload;
    }

    function showDraftBadge(isVisible) {
        if (!badgeElement) {
            return;
        }

        badgeElement.hidden = !isVisible;
    }

    function setSaveButtonState(state) {
        if (!saveButton) {
            return;
        }

        window.clearTimeout(saveButtonResetTimer);

        if (state === "saving") {
            saveButton.disabled = true;
            saveButton.textContent = "Saving Draft...";
            return;
        }

        saveButton.disabled = false;

        if (state === "saved") {
            saveButton.textContent = "Draft Saved";
            saveButtonResetTimer = window.setTimeout(() => {
                saveButton.textContent = "Save Draft";
            }, 1400);
            return;
        }

        if (state === "error") {
            saveButton.textContent = "Draft Not Saved";
            saveButtonResetTimer = window.setTimeout(() => {
                saveButton.textContent = "Save Draft";
            }, 1800);
            return;
        }

        saveButton.textContent = "Save Draft";
    }

    return {
        activate,
        deactivate,
        discard,
        hasChanges,
        markClean,
        saveNow,
        scheduleSave
    };
}

export function collectReservationFormDraft(form, timePicker) {
    const fields = collectNamedFormFields(form);

    return {
        fields: {
            ...fields,
            access: fields.access || "open",
            recurring: Boolean(fields.recurring)
        },
        time_picker_week_start: timePicker.getWeekStart?.()?.toISOString?.() || null,
        time_ranges: timePicker.getRanges().map(range => ({
            start_time: range.start.toISOString(),
            end_time: range.end.toISOString()
        }))
    };
}

export function applyReservationFormDraft(form, timePicker, payload) {
    const fields = payload?.fields || {};

    Object.entries(fields).forEach(([name, value]) => {
        const controls = form.elements[name];

        if (!controls) {
            return;
        }

        if (controls instanceof RadioNodeList) {
            Array.from(controls).forEach(control => {
                if (control.type === "checkbox") {
                    control.checked = Boolean(value);
                    return;
                }

                if (control.type === "radio") {
                    control.checked = String(control.value) === String(value);
                }
            });
            return;
        }

        if (controls.type === "checkbox") {
            controls.checked = Boolean(value);
            return;
        }

        controls.value = value ?? "";
    });

    if (Array.isArray(payload?.time_ranges)) {
        timePicker.setRanges(payload.time_ranges.map(range => ({
            start: range.start_time,
            end: range.end_time
        })));
    } else if (payload?.time_picker_week_start) {
        timePicker.setWeekFromDate(payload.time_picker_week_start);
    }
}

export function clearReservationFormDraftFields(form, timePicker, {
    preserveNames = []
} = {}) {
    const preserveNameSet = new Set(preserveNames);

    Array.from(form.elements).forEach(control => {
        if (!control.name || preserveNameSet.has(control.name)) {
            return;
        }

        if (
            control.type === "button" ||
            control.type === "submit" ||
            control.type === "reset"
        ) {
            return;
        }

        if (control.type === "checkbox") {
            control.checked = false;
            dispatchControlEdit(control);
            return;
        }

        if (control.type === "radio") {
            control.checked = control.value === "open";
            dispatchControlEdit(control);
            return;
        }

        control.value = "";
        dispatchControlEdit(control);
    });

    timePicker.clear();
}

export function showDraftExitPrompt() {
    return new Promise(resolve => {
        const overlay = document.createElement("div");
        const dialog = document.createElement("div");
        const title = document.createElement("h3");
        const message = document.createElement("p");
        const actions = document.createElement("div");
        const discardButton = document.createElement("button");
        const continueButton = document.createElement("button");
        const saveButton = document.createElement("button");

        overlay.className = "draft-exit-prompt";
        dialog.className = "draft-exit-card";
        actions.className = "draft-exit-actions";
        title.textContent = "Save Draft?";
        message.textContent = "You have changes in this reservation form. Save them as a draft or discard them before closing.";

        discardButton.type = "button";
        continueButton.type = "button";
        saveButton.type = "button";
        discardButton.className = "reservation-secondary-btn";
        continueButton.className = "reservation-secondary-btn";
        saveButton.className = "reservation-primary-btn";
        discardButton.textContent = "Discard Changes";
        continueButton.textContent = "Continue Editing";
        saveButton.textContent = "Save Draft";

        function closePrompt(action) {
            overlay.remove();
            resolve(action);
        }

        discardButton.addEventListener("click", () => closePrompt("discard"));
        continueButton.addEventListener("click", () => closePrompt("continue"));
        saveButton.addEventListener("click", () => closePrompt("save"));

        actions.append(discardButton, continueButton, saveButton);
        dialog.append(title, message, actions);
        overlay.appendChild(dialog);
        document.body.appendChild(overlay);
        saveButton.focus();
    });
}

function collectNamedFormFields(form) {
    const fields = {};

    Array.from(form.elements).forEach(control => {
        if (!control.name) {
            return;
        }

        if (
            control.type === "button" ||
            control.type === "submit" ||
            control.type === "reset"
        ) {
            return;
        }

        if (control.type === "radio") {
            if (control.checked) {
                fields[control.name] = control.value;
            } else if (!(control.name in fields)) {
                fields[control.name] = "";
            }
            return;
        }

        if (control.type === "checkbox") {
            fields[control.name] = control.checked;
            return;
        }

        fields[control.name] = control.value;
    });

    return fields;
}

function hasDraftContent(payload) {
    const fieldValues = Object.entries(payload?.fields || {})
        .filter(([fieldName]) => !["id", "host_user_id", "start", "end"].includes(fieldName))
        .map(([, value]) => value);
    const hasFieldContent = fieldValues.some(value => {
        if (typeof value === "boolean") {
            return value;
        }

        return String(value ?? "").trim() !== "";
    });

    return hasFieldContent ||
        Boolean(payload?.time_picker_week_start) ||
        (payload?.time_ranges || []).length > 0;
}

function serializePayload(payload) {
    return JSON.stringify(payload || {});
}

function dispatchControlEdit(control) {
    control.dispatchEvent(new Event("input", { bubbles: true }));
    control.dispatchEvent(new Event("change", { bubbles: true }));
}
