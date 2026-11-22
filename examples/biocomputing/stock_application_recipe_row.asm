; One physical recipe-page envelope -> one catalogue row (or a visible error).
; ROM A.x1 -> x0, x1 -> P.x1. First output is an icon, not an inventory promise.
TYPE R FORMAT recipe 1
TYPE Opt OPTIONAL R
TYPE Checked RECORD ok BOOLEAN value Opt
TYPE In RECORD value ANY
TYPE Out RECORD ok BOOLEAN v ANY
TYPE Data RECORD page ANY fingerprint ANY
TYPE Row RECORD key ANY title ANY unit TEXT icon ANY data Data
REG in In
REG chk Checked
REG recipe Opt
REG out Out
REG data Data
REG row Row
REG v ANY
REG k ANY
REG t ANY
REG c ANY
REG f ANY
REG a ANY
REG ok BOOLEAN
REG z INTEGER
REG busy INTEGER
REG status INTEGER
CONST z 0
CONST busy 15
next:
XBUS.STATUS status x0
EQUAL_INTEGER ok status busy
JFALSE ok pause
XBUS.READ in x0
FIELD v in value
DROP in
FMT.CHECK chk v recipe 1
DROP v
FIELD ok chk ok
JFALSE ok reject
FIELD recipe chk value
DROP chk
FMT.PROJECT v recipe recipe 1
DROP recipe
FIELD k v pattern_id
FIELD f v fingerprint
FIELD a v page
FIELD c v outputs
LIST.GET v c z
FIELD t v item
; A recipe output is already exactly {item,count,components}. Keep its batch
; yield and native patch together. The player's ordered batch quantity is separate.
; The reader has an explicitly selected book. Do not embed its nested NBT locator in each order.
RECORD.MAKE data page a fingerprint f
RECORD.MAKE row key k title t unit unit icon v data data
RECORD.MAKE out ok ok v row
JMP send
reject:
CONST v {"code":"invalid_recipe"}
RECORD.MAKE out ok ok v v
send:
XBUS.WRITE x1 out
JMP next
pause:
SLEEP poll_ticks
JMP next
