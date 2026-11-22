; Buffer owns only explicit pieces; downstream range worker validates their order/size.
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Context RECORD r Row s ANY d ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG context Context
REG tx Result
REG flag BOOLEAN
TYPE Info RECORD bytes INTEGER format TEXT kind TEXT version ANY
TYPE MaybeInfo OPTIONAL Info
TYPE CheckedInfo RECORD ok BOOLEAN value MaybeInfo
TYPE Parts LIST NBT
TYPE Package RECORD o BOOLEAN p Parts k TEXT v ANY
TYPE MaybeBytes OPTIONAL NBT
TYPE CheckedBytes RECORD ok BOOLEAN value MaybeBytes
REG info Info
REG checkedInfo CheckedInfo
REG maybeInfo MaybeInfo
REG checked CheckedBytes
REG maybe MaybeBytes
REG bytes NBT
REG parts Parts
REG packet Package
REG raw ANY
REG version ANY
REG kind TEXT
REG stream BOOLEAN
XBUS.READ context x0
LIST.MAKE parts
FIELD raw context d
VALUE.CHECK checkedInfo raw
FIELD maybeInfo checkedInfo value
REQUIRE_VALUE info maybeInfo
FIELD kind info kind
FIELD version info version
DROP raw
DROP info
DROP checkedInfo
DROP maybeInfo
send:
XBUS.WRITE x1 context
DROP context
next:
XBUS.READ tx x1
FIELD flag tx ok
JFALSE flag rejected
FIELD raw tx value
IS_NULL flag raw
JFALSE flag part
JMP done
part:
VALUE.CHECK checked raw
FIELD maybe checked value
REQUIRE_VALUE bytes maybe
LIST.APPEND parts parts bytes
DROP raw
DROP checked
DROP maybe
DROP bytes
DROP tx
JMP next
rejected:
FIELD version tx value
LIST.MAKE parts
CONST kind ""
done:
RECORD.MAKE packet o flag p parts k kind v version
XBUS.WRITE x0 packet
