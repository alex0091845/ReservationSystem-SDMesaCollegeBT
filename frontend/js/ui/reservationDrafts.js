import { deleteReservationDraft, getReservationDraft, saveReservationDraft } from "../api.js";

const AUTOSAVE_DELAY_MS = 700;

export function createReservationDraftAutosave({
    form,
    timePicker,
    draftType,
    getSourceEventId = () => null,
    getHostUserId = () => null,
    collectPayload,
    applyPayload
}) {
    let active = false;
    let currentDraft = null;
    let isApplyingDraft = false;
    let lastSavedPayload = "";
    let saveTimer = null;
    let pendingSavePromise = null;
    let saveQueued = false;

    if (form) {
        form.addEventListener("input", scheduleSave);
        form.addEventListener("change", scheduleSave);
    }

    async function activate() {
        active = true;
        clearSaveTimer();

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

        lastSavedPayload = serializePayload(collectPayload());

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
            return;
        }

        try {
            await deleteReservationDraft(currentDraft);
        } catch (error) {
            console.error("Could not delete reservation draft:", error);
        } finally {
            currentDraft = null;
            lastSavedPayload = "";
        }
    }

    function scheduleSave() {
        if (!active || isApplyingDraft) {
            return;
        }

        clearSaveTimer();
        saveTimer = window.setTimeout(saveNow, AUTOSAVE_DELAY_MS);
    }

    async function saveNow() {
        clearSaveTimer();

        if (!active || isApplyingDraft) {
            return;
        }

        if (pendingSavePromise) {
            saveQueued = true;
            return;
        }

        const payload = collectPayload();

        if (!hasDraftContent(payload)) {
            return;
        }

        const serializedPayload = serializePayload(payload);

        if (serializedPayload === lastSavedPayload) {
            return;
        }

        try {
            pendingSavePromise = saveReservationDraft({
                id: currentDraft?.id,
                draft_type: draftType,
                source_event_id: getSourceEventId(),
                host_user_id: getHostUserId(),
                payload
            });
            currentDraft = await pendingSavePromise;
            lastSavedPayload = serializedPayload;
        } catch (error) {
            console.error("Could not save reservation draft:", error);
        } finally {
            pendingSavePromise = null;

            if (saveQueued && active) {
                saveQueued = false;
                scheduleSave();
            }
        }
    }

    function clearSaveTimer() {
        if (saveTimer) {
            window.clearTimeout(saveTimer);
            saveTimer = null;
        }
    }

    return {
        activate,
        deactivate,
        discard,
        saveNow,
        scheduleSave
    };
}

export function collectReservationFormDraft(form, timePicker) {
    const formData = new FormData(form);
    const fields = Object.fromEntries(formData.entries());

    return {
        fields: {
            ...fields,
            access: formData.get("access") || "open",
            recurring: Boolean(formData.get("recurring"))
        },
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
    }
}

function hasDraftContent(payload) {
    const fieldValues = Object.entries(payload?.fields || {})
        .filter(([fieldName]) => !["start", "end"].includes(fieldName))
        .map(([, value]) => value);
    const hasFieldContent = fieldValues.some(value => {
        if (typeof value === "boolean") {
            return value;
        }

        return String(value ?? "").trim() !== "";
    });

    return hasFieldContent || (payload?.time_ranges || []).length > 0;
}

function serializePayload(payload) {
    return JSON.stringify(payload || {});
}
