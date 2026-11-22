; No physical UART on this Computer. x1 is the private link to the wire worker.
; One explicit x0 caller supplies p=the full command, e=the expected reply kind.
; There is no host routing table and no protocol privilege. Replies still occupy wire time.
; poll_ticks and poll_limit are editable program inputs, NOT a transport timeout or retry.
TYPE target_type RECORD x INTEGER y INTEGER z INTEGER
TYPE packet_type RECORD kind TEXT version INTEGER target target_type request TEXT operation TEXT arguments ANY
TYPE call_type RECORD p packet_type e TEXT
TYPE result_type RECORD r ANY
TYPE keys_type LIST TEXT
TYPE fields_type LIST ANY
REG call call_type
REG packet packet_type
REG result result_type
REG tx SERIAL_SEND
REG reply ANY
REG expect TEXT
REG a ANY
REG v ANY
REG t ANY
REG r ANY
REG wanted fields_type
REG actual fields_type
REG ok BOOLEAN
REG attempts INTEGER
REG keys keys_type
REG i INTEGER
REG key TEXT
REG one INTEGER
CONST one 1
CONST keys ["kind","version","target","request"]
next:
; A paused peer is invalid, not a readable zero. Poll readiness before starting READ.
; Idle readiness polling spends CPU budget. poll_ticks only paces serial reply polling.
XBUS.STATUS i x0
LESS_INTEGER ok one i
JFALSE ok next
XBUS.READ call x0
FIELD packet call p
FIELD expect call e
FIELD v packet version
FIELD t packet target
FIELD r packet request
LIST.MAKE wanted expect v t r
XBUS.WRITE x1 packet
XBUS.READ tx x1
FIELD ok tx ok
JFALSE ok tx_error
MOV attempts poll_limit
yield:
SLEEP poll_ticks
read:
; tx has no kind field: use it as the explicit poll message.
XBUS.WRITE x1 tx
XBUS.READ reply x1
FIELD reply reply value
LIST.LENGTH i keys
guard:
SUBTRACT_INTEGER i i one
LIST.GET key keys i
HAS.FIELD ok reply key
JFALSE ok wait
LESS_INTEGER ok i one
JFALSE ok guard
FIELD a reply kind
FIELD v reply version
FIELD t reply target
FIELD r reply request
LIST.MAKE actual a v t r
VALUE.EQUALS ok actual wanted
JFALSE ok wait
JMP emit
wait:
SUBTRACT_INTEGER attempts attempts one
LESS_INTEGER ok attempts one
JFALSE ok yield
CONST reply {"code":"receive_timeout"}
JMP emit
tx_error:
CONST reply {"code":"send_failed"}
emit:
RECORD.MAKE result r reply
XBUS.WRITE x0 result
JMP next
