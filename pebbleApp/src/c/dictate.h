#pragma once

// Dictation, for the whole app: one session, its result going to whoever started it.

#include <pebble.h>

// Called with what was said, once it's confirmed.
typedef void (*Dictated)(const char *text);

// Starts dictating, the result going to [dictated]; false when it can't (no phone, or no dictation there).
bool dictate(Dictated dictated);

// [dictated] no longer wants a result (its screen closed): one still coming goes nowhere.
void dictate_forget(Dictated dictated);

void dictate_deinit(void);
