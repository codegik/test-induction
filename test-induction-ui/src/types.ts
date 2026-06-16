export interface Behavior {
  name?: string;
  baseUrl: string;
  method: string;
  path?: string;
  pathPattern?: string;
  response: unknown;
  stubId: string;
}

export interface Group {
  profile: string;
  caller: string;
  behaviors: Behavior[];
}

export type Health = "checking" | "up" | "down";

/** What the register/update form emits (a single WireMock-style behavior). */
export interface BehaviorSpec {
  name?: string;
  match: { baseUrl: string; method: string; path?: string; pathPattern?: string };
  response: unknown;
}

export const behaviorPath = (b: Behavior): string => b.pathPattern ?? b.path ?? "";
export const behaviorUrl = (b: Behavior): string => b.baseUrl + behaviorPath(b);
