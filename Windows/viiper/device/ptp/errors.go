package ptp

import "errors"

// ErrTooManyContacts rejects a frame that reports more contacts than the
// device declared; Windows would discard it anyway.
var ErrTooManyContacts = errors.New("ptp: contact count exceeds maximum")
