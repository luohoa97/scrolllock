const test = require("node:test");
const assert = require("node:assert/strict");
const R = require("../route.js");

test("classifies instagram paths", () => {
  assert.equal(R.classify("/reels/"), "reels");
  assert.equal(R.classify("/reels/C1abc/"), "reels");
  assert.equal(R.classify("/reel/C1abc/"), "reels");
  assert.equal(R.classify("/explore/"), "explore");
  assert.equal(R.classify("/explore/search/"), "explore");
  assert.equal(R.classify("/direct/inbox/"), "dm");
  assert.equal(R.classify("/direct/t/123456/"), "dm");
  assert.equal(R.classify("/accounts/login/"), "account");
  assert.equal(R.classify("/"), "other");
  assert.equal(R.classify("/p/C1abc/"), "other");
  assert.equal(R.classify("/someone/"), "other");
});

test("matching is case-insensitive and tolerant of empty input", () => {
  assert.equal(R.classify("/Reels/"), "reels");
  assert.equal(R.classify(""), "other");
  assert.equal(R.classify(undefined), "other");
});

test("only messages and login pages pass", () => {
  assert.equal(R.isBlocked("/direct/inbox/"), false);
  assert.equal(R.isBlocked("/direct/t/99/"), false);
  assert.equal(R.isBlocked("/accounts/login/"), false);
});

test("everything else is blocked, including the home feed, posts and profiles", () => {
  for (const p of ["/", "/reels/", "/explore/", "/p/C1abc/", "/someone/", "/stories/someone/1/", "/unknown/path/"]) {
    assert.equal(R.isBlocked(p), true, p);
  }
});
