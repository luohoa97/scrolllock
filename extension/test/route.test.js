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
  assert.equal(R.classify("/"), "other");
  assert.equal(R.classify("/p/C1abc/"), "other");
  assert.equal(R.classify("/someone/"), "other");
});

test("matching is case-insensitive and tolerant of empty input", () => {
  assert.equal(R.classify("/Reels/"), "reels");
  assert.equal(R.classify(""), "other");
  assert.equal(R.classify(undefined), "other");
});

test("messages are never blocked", () => {
  assert.equal(R.isBlocked("/direct/inbox/", { blockReels: true, blockExplore: true }), false);
});

test("settings control what is blocked", () => {
  assert.equal(R.isBlocked("/reels/", { blockReels: false, blockExplore: true }), false);
  assert.equal(R.isBlocked("/explore/", { blockReels: true, blockExplore: false }), false);
  assert.equal(R.isBlocked("/reels/", { blockReels: true, blockExplore: true }), true);
  assert.equal(R.isBlocked("/explore/", {}), true);
});
