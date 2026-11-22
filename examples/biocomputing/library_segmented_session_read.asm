; Application selector p0=1 authorizes this startup reader; no whole-value read.
; x0 is the application's existing kick/result connection. x1 joins the shared
; session coordinator; p1 reads its selector (2 means final result for this caller).
TYPE Kick RECORD v INTEGER
TYPE Row RECORD o TEXT d ANY e TEXT l BOOLEAN
TYPE Rows LIST Row
TYPE Query RECORD c Rows m ANY
TYPE Result RECORD ok BOOLEAN value ANY
REG kick Kick
REG query Query
REG result Result
REG empty ANY
REG selected INTEGER
REG finished INTEGER
REG observed INTEGER
REG ready BOOLEAN
CONST selected 1
select:
SLEEP selected
SIO.READ observed p0
EQUAL_INTEGER ready observed selected
JFALSE ready select
XBUS.READ kick x0
CONST empty null
RECORD.MAKE query c c m empty
XBUS.WRITE x1 query
CONST finished 2
wait_result:
SLEEP selected
SIO.READ observed p1
EQUAL_INTEGER ready observed finished
JFALSE ready wait_result
XBUS.READ result x1
XBUS.WRITE x0 result
