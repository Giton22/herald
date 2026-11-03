import test from 'node:test';
import assert from 'node:assert/strict';
import { handoffButtonState, viewModeState } from './client.js';

test('watching shows only Take over; confirmed control shows only Hand back', () => {
  assert.deepEqual(handoffButtonState(false, true), {
    take: { hidden: false, disabled: false }, back: { hidden: true, disabled: true },
  });
  assert.deepEqual(handoffButtonState(true, true), {
    take: { hidden: true, disabled: true }, back: { hidden: false, disabled: false },
  });
  assert.equal(handoffButtonState(false, true).back.hidden, true);
});

test('disconnected controls cannot initiate a handoff', () => {
  for (const owned of [true, false]) {
    const state = handoffButtonState(owned, false);
    assert.equal(state.take.disabled, true);
    assert.equal(state.back.disabled, true);
    assert.notEqual(state.take.hidden, state.back.hidden);
  }
});

test('truthy unresolved ownership never shows Hand back', () => {
  assert.equal(handoffButtonState(Promise.resolve(true), true).back.hidden, true);
});

test('phone mode always fits and hides both scaling actions', () => {
  for (const nativeSize of [false, true]) {
    assert.deepEqual(viewModeState(nativeSize, true), {
      zoom: false, scaleViewport: true, fitHidden: true, zoomHidden: true,
    });
  }
});

test('desktop retains the alternate Fit or 100% action', () => {
  assert.deepEqual(viewModeState(false, false), {
    zoom: false, scaleViewport: true, fitHidden: true, zoomHidden: false,
  });
  assert.deepEqual(viewModeState(true, false), {
    zoom: true, scaleViewport: false, fitHidden: false, zoomHidden: true,
  });
});

test('switching from native desktop mode to phone forces fit', () => {
  const desktop = viewModeState(true, false);
  const phone = viewModeState(desktop.zoom, true);
  assert.equal(phone.zoom, false);
  assert.equal(phone.scaleViewport, true);
  assert.equal(viewModeState(phone.zoom, false).zoom, false);
});
