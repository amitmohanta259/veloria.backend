/** Inputs and the outcome the configured rules produce for them. */
module.exports = {
  // Woven apparel (chapter 62) at or above ₹1,000 across state lines: 12 % IGST.
  interStateApparel: { hsn: '6211', price: '1500', buyerState: '27', sellerState: '29', igstPercent: '12.0', igstRupees: '180', totalRupees: '1,680' },
  // A rule that must appear in the rules table.
  knownRule: { hsn: '4202', description: 'Handbags' },
};
