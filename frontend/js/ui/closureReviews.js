import {
    getClosureNotifications,
    getClosureReview,
    getClosureReviews,
    markClosureReviewed,
    retryClosureNotification,
    sendClosureFollowUp
} from "../api.js";

const TIME_ZONE = "America/Los_Angeles";
const dateTime = new Intl.DateTimeFormat("en-US", {
    timeZone: TIME_ZONE, weekday: "short", month: "short", day: "numeric", year: "numeric",
    hour: "numeric", minute: "2-digit"
});
const time = new Intl.DateTimeFormat("en-US", { timeZone: TIME_ZONE, hour: "numeric", minute: "2-digit" });
const KINDS = {
    canceled: "Cancellation notice to organizer",
    admin_review: "Review summary to admin",
    admin_follow_up: "Follow-up to organizer"
};
const STATUSES = { pending: "Pending", sending: "Sending", sent: "Sent", failed: "Failed", skipped: "Not sent" };
// Plain-language versions of the short codes the backend records.
const DETAILS = {
    send_failed: "The mail server could not be reached. It will be retried automatically.",
    recipient_rejected: "The mail server rejected the address.",
    missing_recipient: "No email address is on file.",
    invalid_recipient: "The email address on file is not valid.",
    unknown_kind: "This email type is not supported.",
    reservation_passed: "Not needed: the reservation time had already passed.",
    recipient_not_admin: "Not sent: the account is no longer an admin."
};
const CAUSES = {
    unavailable_date: "The date was marked unavailable",
    after_last_bookable_date: "The date fell after the last bookable date"
};

function when(row) {
    return `${dateTime.format(new Date(row.event_start_time))} to ${time.format(new Date(row.event_end_time))}`;
}

function errorMessage(error, fallback) {
    if (error?.status === 401 || error?.status === 403) {
        return "Only admins can do this. Sign in again as an admin.";
    }

    return typeof error?.data?.error === "string" ? error.data.error : fallback;
}

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function badge(state, text) {
    return element("span", `admin-closure-badge is-${state}`, text);
}

// Admin follow-up after a confirmed closure. Marking a review complete sends nothing; a follow-up
// email is the admin's own message and the backend only accepts it after the review is complete.
export function createClosureReviews({ container, getUsers }) {
    const filter = container.querySelector("#closureReviewFilter");
    const count = container.querySelector("#closureReviewCount");
    const list = container.querySelector("#closureReviewList");
    const details = container.querySelector("#closureReviewDetails");
    const status = container.querySelector("#closureReviewStatus");
    const deliveryFilter = container.querySelector("#closureDeliveryFilter");
    const deliverySummary = container.querySelector("#closureDeliverySummary");
    const deliveryList = container.querySelector("#closureDeliveryList");
    const deliveryStatus = container.querySelector("#closureDeliveryStatus");
    let reviews = [];
    let notifications = [];
    let selectedId = null;

    function setStatus(target, message, kind = "") {
        target.textContent = message;
        target.classList.toggle("error", kind === "error");
        target.classList.toggle("success", kind === "success");
    }

    function userName(id) {
        const user = getUsers().find(candidate => String(candidate.id) === String(id));
        return user ? `${user.first_name || ""} ${user.last_name || ""}`.trim() || "Unnamed user" : `User ${id}`;
    }

    function renderList() {
        const pending = reviews.filter(review => review.review_status === "pending").length;
        count.textContent = filter.value === "pending"
            ? `${pending} pending`
            : `${reviews.length} shown, ${pending} pending`;
        list.replaceChildren();
        if (!reviews.length) {
            list.appendChild(element("p", "faculty-user-empty", filter.value === "pending"
                ? "Nothing needs review. Reservations canceled by a closure appear here."
                : "No reviews to show."));
            return;
        }
        for (const review of reviews) {
            const item = element("button", "faculty-user-card");
            item.type = "button";
            item.setAttribute("aria-pressed", String(review.id === selectedId));
            const reviewed = review.review_status === "reviewed";
            const text = element("span", "admin-closure-item-text");
            text.append(
                element("span", "faculty-user-name", review.event_title || "Untitled reservation"),
                element("span", "admin-closure-hint", when(review)),
                element("span", "admin-closure-hint",
                    `Organizer: ${`${review.host_first_name || ""} ${review.host_last_name || ""}`.trim() || "Unknown"}`)
            );
            item.append(text, badge(reviewed ? "completed" : "pending", reviewed ? "Review completed" : "Review pending"));
            item.addEventListener("click", () => select(review.id));
            list.appendChild(item);
        }
    }

    function field(listNode, label, value) {
        listNode.append(element("dt", "", label), element("dd", "", value));
    }

    function renderDetails(review) {
        details.replaceChildren();
        if (!review) {
            details.appendChild(element("p", "faculty-user-empty", "Select a reservation to see its details."));
            return;
        }
        const reviewed = review.review_status === "reviewed";
        const header = element("div", "admin-closure-details-header");
        header.append(
            element("h3", "", review.event_title || "Untitled reservation"),
            badge(reviewed ? "completed" : "pending", reviewed ? "Review completed" : "Review pending")
        );
        const facts = element("dl", "admin-closure-facts");
        const organizer = `${review.host_first_name || ""} ${review.host_last_name || ""}`.trim() || "Unknown";
        field(facts, "Original booking", when(review));
        field(facts, "Organizer", organizer);
        field(facts, "Organizer email", review.host_email || "Not on file");
        field(facts, "Department", review.department || "Not given");
        field(facts, "Event type", review.event_type || "Not given");
        field(facts, "Access", review.is_public ? "Open to everyone" : "Private");
        field(facts, "Description", review.event_description || "None");
        field(facts, "Check-ins removed", String(review.check_in_count ?? 0));
        field(facts, "Why it was canceled", CAUSES[review.cause] || "Availability change");
        field(facts, "Closure reason", review.closure_reason || "None given");
        field(facts, "Canceled", `${dateTime.format(new Date(review.canceled_at))} by ${userName(review.canceled_by)}`);
        if (reviewed) {
            field(facts, "Reviewed", `${dateTime.format(new Date(review.reviewed_at))} by ${userName(review.reviewed_by)}`);
            field(facts, "Review note", review.review_note || "None");
        }

        const emails = element("div", "admin-closure-emails");
        emails.appendChild(element("h4", "", "Emails for this reservation"));
        const sent = (review.closure_notifications || []).slice().sort((a, b) => a.id - b.id);
        if (!sent.length) emails.appendChild(element("p", "admin-closure-hint", "No emails yet."));
        for (const notification of sent) {
            const row = element("p", "admin-closure-email-row");
            row.append(
                element("span", "", KINDS[notification.kind] || "Email"),
                badge(notification.status, STATUSES[notification.status] || notification.status)
            );
            emails.appendChild(row);
        }

        const action = element("form", "admin-closure-action");
        const label = element("label", "modal-label");
        const input = element("textarea", "reservation-input reservation-textarea");
        input.id = "closureReviewInput";
        input.maxLength = 2000;
        input.rows = 3;
        label.htmlFor = input.id;
        const button = element("button", "reservation-primary-btn");
        button.type = "submit";
        if (reviewed) {
            label.textContent = `Follow-up email to ${organizer}`;
            input.required = true;
            const hint = element("p", "admin-closure-hint",
                "Write what changed after your review. It is emailed to the organizer in your words, " +
                "and says the original reservation stays canceled unless your message says otherwise.");
            button.textContent = "Send follow-up";
            action.append(label, input, hint, button);
        } else {
            label.textContent = "Review note (optional)";
            const hint = element("p", "admin-closure-hint",
                "Marking the review complete sends nobody anything. You can send the organizer a follow-up afterwards.");
            button.textContent = "Mark reviewed";
            action.append(label, input, hint, button);
        }
        action.addEventListener("submit", async event => {
            event.preventDefault();
            if (!action.reportValidity()) return;
            if (reviewed && !window.confirm(`Email this follow-up to ${organizer}?`)) return;
            button.disabled = true;
            try {
                if (reviewed) {
                    await sendClosureFollowUp(review.id, input.value.trim());
                    setStatus(status, `Follow-up queued for ${organizer}. Its delivery shows under Email delivery.`, "success");
                } else {
                    await markClosureReviewed(review.id, input.value.trim());
                    setStatus(status, "Review marked complete.", "success");
                }
                await load();
            } catch (error) {
                setStatus(status, errorMessage(error, "That did not work. Please try again."), "error");
                button.disabled = false;
            }
        });

        details.append(header, facts, emails, action);
    }

    async function select(id, { keepStatus = false } = {}) {
        selectedId = id;
        renderList();
        if (!keepStatus) setStatus(status, "");
        const cached = reviews.find(review => review.id === id);
        if (cached) {
            renderDetails(cached);
            return;
        }
        try {
            renderDetails(await getClosureReview(id));
        } catch (error) {
            selectedId = null;
            renderDetails(null);
            setStatus(status, errorMessage(error, "This review could not be loaded."), "error");
        }
    }

    function renderDelivery() {
        const counts = { pending: 0, sent: 0, failed: 0 };
        for (const notification of notifications) {
            const key = notification.status === "sending" ? "pending" : notification.status;
            if (key in counts) counts[key] += 1;
        }
        deliverySummary.textContent = `${counts.pending} pending, ${counts.sent} sent, ${counts.failed} failed`;
        const shown = notifications.filter(notification => deliveryFilter.value === "all" ||
            notification.status === deliveryFilter.value ||
            (deliveryFilter.value === "pending" && notification.status === "sending"));
        deliveryList.replaceChildren();
        if (!shown.length) {
            deliveryList.appendChild(element("p", "faculty-user-empty", "No emails to show."));
            return;
        }
        const table = element("table", "admin-closure-table");
        const head = element("tr");
        ["Email", "To", "Status", "Details", ""].forEach(text => head.appendChild(element("th", "", text)));
        table.appendChild(element("thead")).appendChild(head);
        const body = table.appendChild(element("tbody"));
        for (const notification of shown) {
            const row = element("tr");
            const statusCell = element("td");
            statusCell.appendChild(badge(notification.status, STATUSES[notification.status] || notification.status));
            const detail = notification.status === "sent"
                ? `Sent ${dateTime.format(new Date(notification.sent_at))}`
                : DETAILS[notification.last_error] || `Queued ${dateTime.format(new Date(notification.created_at))}`;
            const actionCell = element("td");
            if (notification.status === "failed") {
                const retry = element("button", "reservation-secondary-btn", "Retry");
                retry.type = "button";
                retry.addEventListener("click", async () => {
                    retry.disabled = true;
                    try {
                        const result = await retryClosureNotification(notification.id);
                        setStatus(deliveryStatus, result?.requeued
                            ? "Queued to send again."
                            : "This email no longer needs a retry.", "success");
                        await load();
                    } catch (error) {
                        setStatus(deliveryStatus, errorMessage(error, "The email could not be queued again."), "error");
                        retry.disabled = false;
                    }
                });
                actionCell.appendChild(retry);
            }
            row.append(
                element("td", "", KINDS[notification.kind] || "Email"),
                element("td", "", notification.recipient_user_id ? userName(notification.recipient_user_id) : "Unknown"),
                statusCell,
                element("td", "", detail),
                actionCell
            );
            body.appendChild(row);
        }
        deliveryList.appendChild(table);
    }

    async function load() {
        const [reviewResult, deliveryResult] = await Promise.allSettled([
            getClosureReviews(filter.value),
            getClosureNotifications()
        ]);
        if (reviewResult.status === "fulfilled") {
            reviews = reviewResult.value;
            const selected = reviews.find(review => review.id === selectedId);
            renderList();
            if (selected) renderDetails(selected);
            else if (selectedId === null) renderDetails(null);
            else await select(selectedId, { keepStatus: true });
        } else {
            setStatus(status, errorMessage(reviewResult.reason, "Closure reviews could not be loaded."), "error");
        }
        if (deliveryResult.status === "fulfilled") {
            notifications = deliveryResult.value;
            renderDelivery();
        } else {
            setStatus(deliveryStatus, errorMessage(deliveryResult.reason, "Email delivery could not be loaded."), "error");
        }
    }

    filter.addEventListener("change", () => {
        selectedId = null;
        load();
    });
    deliveryFilter.addEventListener("change", renderDelivery);
    container.querySelector("#closureReviewReload").addEventListener("click", () => {
        setStatus(status, "");
        setStatus(deliveryStatus, "");
        load();
    });

    return { load };
}
