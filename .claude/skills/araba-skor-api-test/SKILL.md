---
name: araba-skor-api-test
description: Runs an end-to-end smoke test of the araba-skor-360-server API (cars + reviews endpoints, including a real Firebase login to test authenticated review submission/delete). Use this whenever the user wants to test or verify the araba-skor-360-server API, check whether a local run or Railway deploy is working end-to-end, or asks things like "test the reviews endpoint", "get a firebase token and test the API", "smoke test araba skor", "verify the review flow still works" — even if they don't name this skill directly.
---

# Araba Skor 360 — API smoke test

Exercises every araba-skor-360-server endpoint against a live instance (local dev or a Railway
deployment), including the full auth-required review flow (upsert → get mine → list → delete).
Reuse this instead of manually reconstructing curl commands and Firebase token requests each time
— that manual process is exactly what this skill replaces.

## Before you start

You need three secrets to get a Firebase token for the auth-required endpoints:
- `FIREBASE_WEB_API_KEY` — the `arabaskor360` Firebase project's Web API Key
- `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD` — a real (or disposable test) Firebase user's login

Check `.env` in the project root for these first. If missing, ask the user for them — never guess
or silently reuse a password seen earlier in the conversation without the user re-confirming it's
current, and never print the password back once you have it (it's a credential, not a result).

Figure out which `base_url` to test against — ask the user if it's not obvious from context:
- `http://localhost:7070` — local dev server (confirm it's actually running first, e.g. `curl -s
  -o /dev/null -w '%{http_code}' http://localhost:7070/health`; if not, say so rather than
  reporting confusing connection-refused failures for every step)
- A Railway production URL, if the user gives one or one was used earlier in the conversation

## Step 1: Get a token (with caching)

Firebase ID tokens last exactly 1 hour. Don't fetch a new one on every run — check
`.claude/skills/araba-skor-api-test/.token-cache.json` first (gitignored, holds `{"token":
"...", "exp": <unix ts>, "email": "..."}` from the last fetch). If it exists, and its `email`
matches the one you're about to use, and its `exp` is more than 5 minutes in the future, reuse
`token` as-is — don't decode the JWT again, the cache already has the decoded expiry.

Otherwise fetch a fresh one:

```bash
curl -s "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$FIREBASE_WEB_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$FIREBASE_TEST_EMAIL\",\"password\":\"$FIREBASE_TEST_PASSWORD\",\"returnSecureToken\":true}"
```

Extract `idToken` from the response. Decode its own `exp` claim (base64-decode the JWT's middle
segment, pad to a multiple of 4 with `=`, parse as JSON) rather than trusting the response's
`expiresIn` field plus current time — the token's own claim is the ground truth. Write
`{"token": idToken, "exp": <that exp>, "email": FIREBASE_TEST_EMAIL}` to the cache file.

If this call fails, stop and report the exact error (Firebase's error responses are
self-explanatory, e.g. `INVALID_LOGIN_CREDENTIALS`) — don't proceed to test authenticated
endpoints with no valid token, every one of them will fail for an uninteresting reason and bury
the actual signal.

## Step 2: Run the endpoint checks, in this order

Run each check and record pass/fail with the actual status code and a one-line reason for any
failure — show what actually came back, "failed" alone isn't useful. Use a car id that actually
exists: step 2's `GET /api/cars` tells you which ones do, take the first result's `id` for the
rest of the checks unless the user specified a particular car.

1. `GET {base_url}/health` → expect `200` and `{"status":"ok"}`
2. `GET {base_url}/api/cars` → expect `200` and a non-empty JSON array. Note one `id` for later steps.
3. `GET {base_url}/api/cars/{id}` → expect `200`, body's `id` matches
4. `GET {base_url}/api/cars/{id}/reviews` (no auth header) → expect `200` — this endpoint is
   intentionally public, a 401 here is a regression, not correct behavior
5. `POST {base_url}/api/cars/{id}/reviews` with `Authorization: Bearer <token>`, body
   `{"score": 85, "comment": "smoke test <timestamp>"}` → expect `200`, response `score == 85`
6. `GET {base_url}/api/cars/{id}/reviews/me` with the same auth header → expect `200`, same
   review as step 5
7. `GET {base_url}/api/cars/{id}/reviews` again (no auth) → expect the step-5 review to now
   appear in the list
8. `DELETE {base_url}/api/cars/{id}/reviews/me` with auth header → expect `204`
9. `GET {base_url}/api/cars/{id}/reviews/me` with auth header → expect `404` (confirms the delete worked)
10. `POST {base_url}/api/cars/{id}/reviews` with **no** `Authorization` header → expect `401`
11. `POST {base_url}/api/cars/{id}/reviews` with auth header, body `{"score": 0}` → expect `400`
    (score range validation)

Steps 5-9 both create and clean up test data on the target server — always run the delete (step
8) even if an earlier step in that chain failed, so a partial run doesn't leave stray review rows
behind for the test user.

## Step 3: Report

Give a compact pass/fail table (endpoint, expected vs actual status, one-line note) — not a wall
of raw curl output. If everything passed, say so briefly and stop there. If something failed,
lead with that, and if it looks like a known failure mode, say so explicitly rather than making
the user re-diagnose it:
- **401/503 on the auth-required steps** → almost always the auth chain to user-platform-service
  (token expired, wrong project's Firebase credentials configured there, or user-platform-service
  itself down/misconfigured) — read the "Auth is fully delegated" section of `CLAUDE.md` in the
  project root before speculating further.
- **Connection refused** → wrong `base_url`, or a local server that isn't actually running.
- **500 on a specific endpoint only** → check that server's logs, don't guess from the outside.
