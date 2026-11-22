; x0 receives original source + dispatch scope only after p1=1.
; x1 shares selected read.x0 (p0=1) and selected check.x0 (p0=2).
; Read failure never becomes an empty ledger. Replies contain no new identity before verified archival.
TYPE Target RECORD x INTEGER y INTEGER z INTEGER
TYPE Scope RECORD source TEXT device TEXT revision INTEGER target Target address TEXT
TYPE Head RECORD kind TEXT sequence INTEGER player TEXT address TEXT order NBT
TYPE Input RECORD d TEXT h Head r Scope
TYPE Kick RECORD v INTEGER
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Request RECORD s Input book ANY
TYPE Reply RECORD c TEXT j TEXT
REG source Input
REG kick Kick
REG result Result
REG request Request
REG reply Reply
REG book ANY
REG one INTEGER
REG two INTEGER
REG zero INTEGER
REG selection INTEGER
REG ok BOOLEAN
REG head Head
REG scope Scope
REG expected TEXT
REG actual TEXT
CONST one 1
CONST two 2
CONST zero 0
CONST kick {"v":0}
CONST reply {"c":"archive_read_failed","j":""}
ready:
SLEEP one
SIO.READ selection p1
EQUAL_INTEGER ok selection one
JFALSE ok ready
XBUS.READ source x0
FIELD head source h
FIELD expected head address
FIELD scope source r
FIELD actual scope address
VALUE.EQUALS ok actual expected
JFALSE ok mismatch
SIO.WRITE p0 one
XBUS.WRITE x1 kick
XBUS.READ result x1
FIELD ok result ok
JFALSE ok done
FIELD book result value
RECORD.MAKE request s source book book
SIO.WRITE p0 two
XBUS.WRITE x1 request
XBUS.READ reply x1
JMP done
mismatch:
CONST reply {"c":"route_mismatch","j":""}
done:
SIO.WRITE p0 zero
XBUS.WRITE x0 reply
