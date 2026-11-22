; Context preparation: upstream coordinator x0, explicit byte-slicing Computer x1.
; One managed task owns one request. TaskJoin retires receipts only after completion.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Job RECORD c Context s BOOLEAN n INTEGER b NBT
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Compact RECORD s ANY v ANY
TYPE Header RECORD volume TEXT entry TEXT expected ANY kind TEXT format TEXT bytes INTEGER
TYPE Transfer RECORD h Header b NBT
TYPE MaybeTransfer OPTIONAL Transfer
TYPE CheckedTransfer RECORD ok BOOLEAN value MaybeTransfer
TYPE Integers LIST INTEGER
REG context Context
REG job Job
REG row Row
REG result Result
REG compact Compact
REG header Header
REG raw ANY
REG transfer Transfer
REG maybeTransfer MaybeTransfer
REG checkedTransfer CheckedTransfer
REG sequence ANY
REG operation TEXT
REG write TEXT
REG bytes NBT
REG members Integers
REG length INTEGER
REG flag BOOLEAN
LIST.MAKE members
NBT.PACK bytes 7 members
CONST write "write"
CONST length 0
XBUS.READ context x0
FIELD row context r
FIELD operation row o
VALUE.EQUALS flag operation write
JFALSE flag send
FIELD raw context d
VALUE.CHECK checkedTransfer raw
DROP raw
FIELD maybeTransfer checkedTransfer value
DROP checkedTransfer
REQUIRE_VALUE transfer maybeTransfer
DROP maybeTransfer
FIELD bytes transfer b
FIELD header transfer h
DROP transfer
NBT.LENGTH length bytes
FIELD sequence context s
CONST row {"o":"write_begin","d":null,"e":"receiving","l":false}
RECORD.MAKE context r row s sequence d header
send:
RECORD.MAKE job c context s flag n length b bytes
DROP bytes
DROP context
XBUS.WRITE x1 job
DROP job
XBUS.READ result x1
; Explicit software projection for the matching finite coordinator. All successful
; replies have already passed correlation and expected-code checks downstream.
; Keep the complete value and operation sequence; failed outcomes pass unchanged.
FIELD flag result ok
JFALSE flag reply
FIELD raw result value
FIELD sequence raw sequence
FIELD raw raw value
RECORD.MAKE compact s sequence v raw
RECORD.MAKE result ok flag value compact
reply:
XBUS.WRITE x0 result
RET
