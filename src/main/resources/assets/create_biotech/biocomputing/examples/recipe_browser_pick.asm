; Submit this row for human review. XBus completion does not prove receipt:
; inspect the receiving desk before confirming. No order or retry here.
TYPE Ref RECORD k TEXT page INTEGER fingerprint TEXT source NBT
TYPE Pick RECORD s TEXT d Ref b TEXT o ANY
REG ref Ref
REG pick Pick
REG j TEXT
REG code TEXT
CORRELATION j
RECORD.MAKE ref k pattern_id page page fingerprint fingerprint source source
RECORD.MAKE pick s j d ref b batch_text o outputs
XBUS.WRITE x1 pick
CONST code "check_confirmation_desk"
