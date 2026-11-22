; Receives only publications (p0=15); decoder uses level 1. A release observed before every reply
; keeps a paused reader from registering in the other consumer's phase.
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Packet RECORD c ANY v ANY
TYPE Command RECORD n INTEGER c ANY
TYPE M OPTIONAL TEXT
TYPE Checked RECORD ok BOOLEAN value M
REG checked Checked
REG tx Result
REG packet Packet
REG command Command
REG data ANY
REG next INTEGER
REG expect ANY
REG at ANY
REG size ANY
REG high INTEGER
REG mode INTEGER
REG zero INTEGER
REG one INTEGER
REG code ANY
REG flag BOOLEAN
CONST expect 0
CONST next 0
CONST zero 0
CONST one 1
CONST high 15
wait:
SIO.READ mode p0
EQUAL_INTEGER flag mode high
JFALSE flag asleep
XBUS.READ tx x0
FIELD data tx value
FIELD flag tx ok
DROP tx
JFALSE flag device_failure
FIELD at data page
VALUE.EQUALS flag at next
JFALSE flag changed
FIELD size data pages
EQUAL_INTEGER flag next zero
JFALSE flag check_size
MOV expect size
check_size:
VALUE.EQUALS flag size expect
JFALSE flag changed
CONST code ""
RECORD.MAKE packet c code v data
XBUS.WRITE x1 packet
DROP packet
DROP data
ADD_INTEGER next next one
VALUE.EQUALS flag next size
JFALSE flag receipt
JMP seal
asleep:
SLEEP poll_ticks
JMP wait
device_failure:
VALUE.CHECK checked data
FIELD flag checked ok
JFALSE flag reply_code
MOV code data
JMP seal
reply_code:
FIELD code data code
JMP seal
changed:
CONST code "changed"
seal:
CONST data null
RECORD.MAKE packet c code v data
XBUS.WRITE x1 packet
CONST next -1
receipt:
RECORD.MAKE command n next c code
released:
SIO.READ mode p0
EQUAL_INTEGER flag mode zero
JFALSE flag sleep_release
XBUS.WRITE x0 command
LESS_INTEGER flag next zero
JFALSE flag wait
RET
sleep_release:
SLEEP poll_ticks
JMP released
