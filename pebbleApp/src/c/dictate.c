#include "dictate.h"

// Longer than an ask's said (talk's s_said) or an answer the phone takes.
#define MAX_TEXT 300

static DictationSession *s_session;
static Dictated s_dictated;

static void prv_status(DictationSession *session, DictationSessionStatus status, char *transcription, void *context) {
  Dictated dictated = s_dictated;
  s_dictated = NULL;
  if (status == DictationSessionStatusSuccess && dictated) dictated(transcription);
}

bool dictate(Dictated dictated) {
  if (!s_session) s_session = dictation_session_create(MAX_TEXT, prv_status, NULL);
  if (!s_session) return false;
  s_dictated = dictated;
  if (dictation_session_start(s_session) == DictationSessionStatusSuccess) return true;
  s_dictated = NULL;
  return false;
}

void dictate_forget(Dictated dictated) {
  if (s_dictated == dictated) s_dictated = NULL;
}

void dictate_deinit(void) {
  if (s_session) dictation_session_destroy(s_session);
  s_session = NULL;
  s_dictated = NULL;
}
