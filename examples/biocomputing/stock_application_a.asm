; A: single granted serial authority. Both external adapters share this finite wire.
; x0 is the explicitly paired Computer B. Never retransmit a command.
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE result_type RECORD r ANY
TYPE keys_type LIST TEXT
REG packet packet_type
REG result result_type
REG tx SERIAL_SEND
REG rx SERIAL_RECEIVE
REG reply ANY
REG expect TEXT
REG reader TEXT
REG a ANY
REG b ANY
REG ok BOOLEAN
REG attempts INTEGER
REG keys keys_type
REG i INTEGER
REG key TEXT
REG one INTEGER
REG delay INTEGER
CONST one 1
CONST delay 20
CONST keys ["kind","version","target","request"]
CONST reader "reader_command"
next:
XBUS.READ packet x0
FIELD a packet kind
VALUE.EQUALS ok a reader
CONST expect "stock_application_reply"
JFALSE ok send
CONST expect "reader_reply"
send:
SERIAL.SEND s0 packet tx
FIELD ok tx ok
JFALSE ok tx_error
CONST attempts 80
read:
SERIAL.RECEIVE rx s0
FIELD ok rx ok
JFALSE ok wait
FIELD reply rx value
LIST.LENGTH i keys
guard:
SUBTRACT_INTEGER i i one
LIST.GET key keys i
HAS.FIELD ok reply key
JFALSE ok wait
LESS_INTEGER ok i one
JFALSE ok guard
FIELD a reply kind
VALUE.EQUALS ok a expect
JFALSE ok wait
FIELD a reply version
FIELD b packet version
VALUE.EQUALS ok a b
JFALSE ok wait
FIELD a reply target
FIELD b packet target
VALUE.EQUALS ok a b
JFALSE ok wait
FIELD a reply request
FIELD b packet request
VALUE.EQUALS ok a b
JFALSE ok wait
JMP emit
wait:
SUBTRACT_INTEGER attempts attempts one
LESS_INTEGER ok attempts one
JFALSE ok yield
CONST reply {"code":"receive_timeout"}
JMP emit
yield:
SLEEP delay
JMP read
tx_error:
CONST reply {"code":"send_failed"}
emit:
RECORD.MAKE result r reply
XBUS.WRITE x0 result
JMP next
