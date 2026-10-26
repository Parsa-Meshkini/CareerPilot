export function deadlineLabel(job) {
  if (job.days_left === null || job.days_left === undefined) return job.deadline_text || "No deadline";
  if (job.days_left < 0) return `Closed ${-job.days_left}d ago`;
  if (job.days_left === 0) return "Due today";
  return `${job.days_left}d left`;
}

export function scoreTone(score) {
  if (score === null || score === undefined) return "muted";
  if (score >= 75) return "good";
  if (score >= 55) return "warn";
  return "bad";
}

export function titleCase(s) {
  return (s || "").toLowerCase().replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

export function actionLabel(action) {
  return { mark_prepared: "Mark prepared", submit: "I submitted it", schedule_interview: "Interview scheduled",
    receive_offer: "Got an offer", reject: "Rejected", accept: "Accept offer", decline: "Decline offer",
    withdraw: "Withdraw" }[action] || titleCase(action);
}

export function when(iso) {
  if (!iso) return "";
  return new Date(iso).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}
