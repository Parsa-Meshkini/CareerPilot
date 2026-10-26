import { describe, expect, it } from "vitest";
import { errorMessage } from "../src/api";
import { actionLabel, deadlineLabel, scoreTone, titleCase } from "../src/format";

describe("errorMessage", () => {
  it("shows domain message with field errors", () => {
    expect(errorMessage({ message: "invalid profile fact", fields: { title: "required" } }, 400))
      .toBe("invalid profile fact (title: required)");
  });
  it("falls back to DRF style errors", () => {
    expect(errorMessage({ email: ["already exists"] }, 400)).toBe("email: already exists");
    expect(errorMessage(null, 500)).toBe("Request failed (500)");
  });
});

describe("formatters", () => {
  it("labels deadlines", () => {
    expect(deadlineLabel({ days_left: 3 })).toBe("3d left");
    expect(deadlineLabel({ days_left: -2 })).toBe("Closed 2d ago");
    expect(deadlineLabel({ days_left: null, deadline_text: "" })).toBe("No deadline");
  });
  it("maps scores and actions", () => {
    expect(scoreTone(80)).toBe("good");
    expect(scoreTone(null)).toBe("muted");
    expect(actionLabel("submit")).toBe("I submitted it");
    expect(titleCase("CAREER_SWITCH")).toBe("Career Switch");
  });
});
