; Explicit segmented archive write. Encode in the owning store; never in a hidden transport. x0 receives {v: candidate ledger, expected: original read version}.
; x1 connects to archive coordinator.x1; p0 reads its selector. Encode before dispatch; wait for selector 2 before receiving the result.
; A later stat NEVER replaces the original version; stale candidates must fail, not erase a newer ledger.
TYPE Request RECORD v ANY expected ANY
TYPE Kick RECORD v INTEGER
TYPE Result RECORD ok BOOLEAN value ANY
TYPE Header RECORD volume TEXT entry TEXT expected ANY kind TEXT format TEXT bytes INTEGER
TYPE Transfer RECORD h Header b NBT
REG request Request
REG selector INTEGER
REG finished INTEGER
REG wait_tick INTEGER
REG result Result
REG header Header
REG transfer Transfer
REG bytes NBT
REG length INTEGER
REG replacement ANY
REG expected ANY
REG snapshot ANY
REG actual ANY
REG version ANY
REG medium ANY
REG old_medium ANY
REG flag BOOLEAN
REG zero INTEGER
REG operation TEXT
REG kind TEXT
XBUS.READ request x0
FIELD replacement request v
FIELD expected request expected
CONST operation "typed_record_v1"
VALUE.ENCODE bytes replacement capacity
NBT.LENGTH length bytes
CONST kind "DATA"
RECORD.MAKE header volume volume entry entry expected expected kind kind format operation bytes length
RECORD.MAKE transfer h header b bytes
XBUS.WRITE x1 transfer
CONST finished 2
CONST wait_tick 1
wait_result:
SLEEP wait_tick
SIO.READ selector p0
EQUAL_INTEGER flag selector finished
JFALSE flag wait_result
XBUS.READ result x1
FIELD flag result ok
JFALSE flag done
FIELD snapshot result value
FIELD actual snapshot value
VALUE.EQUALS flag actual replacement
JFALSE flag mismatch
FIELD version snapshot version
FIELD medium version medium
FIELD old_medium expected medium
VALUE.EQUALS flag medium old_medium
JFALSE flag mismatch
JMP done
mismatch:
CONST result {"ok":false,"value":"archive_readback_mismatch"}
done:
XBUS.WRITE x0 result
