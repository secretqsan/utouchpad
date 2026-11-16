package ptp

import "errors"

// ErrTooManyContacts is returned when a client frame declares more contacts
// than the device announced in its Contact Count Maximum feature report.
// Windows would discard such a frame, so it is rejected at the boundary.
var ErrTooManyContacts = errors.New("ptp: contact count exceeds maximum")
