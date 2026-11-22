; One Computer's real serial pin serves both Library and Speaker. No addressing bypass or send retry.
TYPE Call RECORD p ANY e TEXT
TYPE Reply RECORD r ANY
TYPE Fields LIST ANY
TYPE Keys LIST TEXT
TYPE SerialError RECORD code ANY
REG call Call
REG packet ANY
REG result Reply
REG error SerialError
REG tx SERIAL_SEND
REG rx SERIAL_RECEIVE
REG reply ANY
REG expected TEXT
REG a ANY
REG t ANY
REG r ANY
REG wanted Fields
REG found Fields
REG keys Keys
REG k TEXT
REG n INTEGER
REG i INTEGER
REG one INTEGER
REG ok BOOLEAN
CONST one 1
CONST keys ["kind","target","request"]
next:
SLEEP poll_ticks
XBUS.STATUS i x0
LESS_INTEGER ok one i
JFALSE ok next
XBUS.READ call x0
FIELD packet call p
FIELD expected call e
FIELD t packet target
FIELD r packet request
LIST.MAKE wanted expected t r
SERIAL.SEND s0 packet tx
FIELD ok tx ok
JFALSE ok send_failure
MOV n poll_limit
listen:
SLEEP poll_ticks
SERIAL.RECEIVE rx s0
FIELD ok rx ok
JFALSE ok unmatched
FIELD reply rx value
LIST.LENGTH i keys
guard:
SUBTRACT_INTEGER i i one
LIST.GET k keys i
HAS.FIELD ok reply k
JFALSE ok unmatched
LESS_INTEGER ok i one
JFALSE ok guard
FIELD a reply kind
FIELD t reply target
FIELD r reply request
LIST.MAKE found a t r
VALUE.EQUALS ok found wanted
JFALSE ok unmatched
JMP emit
unmatched:
SUBTRACT_INTEGER n n one
LESS_INTEGER ok n one
JFALSE ok listen
CONST reply {"code":"receive_timeout"}
JMP emit
send_failure:
FIELD reply tx status
RECORD.MAKE error code reply
MOV reply error
emit:
RECORD.MAKE result r reply
XBUS.WRITE x0 result
JMP next
