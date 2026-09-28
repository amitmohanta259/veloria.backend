// Nothing to undo.
//
// Since P0-13 the browser suite does not change COD configuration — the shipped
// configuration is already the approved one, and global-setup only verifies it. This
// file remains as the place to put a teardown if the suite ever does mutate global
// state again, and as the record of why it currently does not.
async function globalTeardown() {}

module.exports = globalTeardown;
