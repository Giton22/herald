#pragma once

// The recent chats, to pick the one the watch talks in, or a new one.

#include "link.h"

// Called with the picked chat and the gateway and profile it's in (empty when the phone doesn't say); an empty id is
// a new chat.
typedef void (*ChatPicked)(const char *chat_id, const char *title, const char *scope);

// Opens the list, marking [current], and asks the phone for the chats; [fill] and [on_fill] are the accent.
void chats_open(const char *current, GColor fill, GColor on_fill, ChatPicked picked);

// What the link hands over while the list is open.
void chats_on_item(const LinkChatItem *item);
