import assert from 'node:assert/strict';

// Runs against CI's disposable Docker Compose database only.
// Never point at development or production, since this creates real records.
const api = process.env.IIG_TEST_API || 'http://localhost:8080/api';
const password = process.env.IIG_TEST_PASSWORD || '123456';
const token = new Map();

function dateOffset(days) {
  const time = new Date(Date.now() + 7 * 3600_000);
  const d = new Date(Date.UTC(time.getUTCFullYear(), time.getUTCMonth(), time.getUTCDate() + days));
  return d.toISOString().slice(0, 10);
}
function weekday(date) {
  return ['sun','mon','tue','wed','thu','fri','sat'][
    new Date(date + 'T00:00:00Z').getUTCDay()];
}

async function send(role, method, path, data, expected = 200) {
  const response = await fetch(api + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token.has(role) ? { Authorization: 'Bearer ' + token.get(role) } : {}),
    },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
  });
  const text = await response.text();
  assert.equal(response.status, expected,
    role + ' ' + method + ' ' + path + ' expected HTTP ' + expected +
    ' but received ' + response.status + ': ' + text.slice(0, 1100));
  let result;
  try { result = JSON.parse(text); } catch { result = text; }
  return result;
}
async function login(role) {
  const credentials = await send(role,'POST','/auth/login',
    {username:role,password},200);
  assert.ok(credentials.token, 'token for ' + role);
  token.set(role,credentials.token);
}
async function createClass(name, beginning, ending, lessons) {
  const details = await send('cs001','POST','/classes',{
    courseId:'course-ielts',
    classCode:'CI-' + name + '-' + Date.now().toString(36).slice(-7).toUpperCase(),
    name:'Class integrity ' + name,
    startDate:beginning,
    endDate:ending,
    targetRequirements:[{targetType:'OVERALL_BAND',requiredTarget:5}],
    plannedSessions:lessons,
  },201);
  assert.equal(details.status,'DRAFT');
  return details.classId;
}
async function sessions(classId) {
  return send('cs001','GET','/classes/' + classId + '/planned-sessions');
}
async function registerAndApprove(employee,reviewer,day) {
  const slot = await send(employee,'POST','/availabilities',{
    availableDate:null,dayOfWeek:weekday(day),shift:'MORNING',
    startTime:'08:30',endTime:'11:30',availabilityType:'RECURRING',
    campus:'Cơ sở chính',note:'isolated class-integrity test',
  });
  assert.equal(slot.status,'PENDING');
  const reviewed = await send(reviewer,'PATCH',
    '/availabilities/' + slot.id + '/review',{status:'APPROVED'});
  assert.equal(reviewed.status,'APPROVED');
}
async function range(classId,role,person,ids,mode='FILL_EMPTY') {
  return send(role==='TEACHER'?'tc001':'cm001',
    'POST','/class-planning/' + classId + '/staffing',{
      role, employeeId:person, campus:'Cơ sở chính', workplaceId:'center-01',
      scope:role==='CS'?'COURSE':'SINGLE', mode, sessionIds:ids,
    });
}

for(const role of ['cs001','cs003','teacher003','teacher002','tc001','cm001']){
  await login(role);
}

// 1. Different classes + same student: time windows cannot overlap.
// Adjacent 18:00–20:00 and 20:00–22:00 are allowed.
const future = dateOffset(200);
const past = dateOffset(-7);
const dayAfterFuture = dateOffset(201);
const a = await createClass('A',future,dayAfterFuture,
  [{date:future,startTime:'18:00',endTime:'20:00'}]);
const b = await createClass('B',future,dayAfterFuture,
  [{date:future,startTime:'19:00',endTime:'21:00'}]);
const c = await createClass('C',future,dayAfterFuture,
  [{date:future,startTime:'20:00',endTime:'22:00'}]);
await send('cs001','POST','/classes/' + a + '/students',
  {studentIds:['student-0001']});
const overlap = await send('cs001','POST','/classes/' + b + '/students',
  {studentIds:['student-0001']},409);
assert.match(JSON.stringify(overlap),/class lesson|ineligible|conflict/i);
await send('cs001','POST','/classes/' + c + '/students',
  {studentIds:['student-0001']});
const blockedPlan = await send('cs001','PUT',
  '/classes/' + c + '/planned-sessions',
  [{date:future,startTime:'19:30',endTime:'22:00'}],409);
assert.match(JSON.stringify(blockedPlan),/STUDENT_SCHEDULE_CONFLICT/);
const unchanged = await sessions(c);
assert.equal(String(unchanged[0].startTime).slice(0,5),'20:00');
console.log('PASS: enrolled student cannot overlap another class; adjacent slots remain valid');

// 2. Past class: teacher absence, substitute, archive history and completion.
const taughtClass = await createClass('PAST',dateOffset(-8),dateOffset(-6),
  [{date:past,startTime:'09:00',endTime:'11:00'}]);
const [lesson] = await sessions(taughtClass);
await send('cs001','PATCH','/classes/' + taughtClass + '/status',{status:'READY'});
await registerAndApprove('teacher003','tc001',past);
await registerAndApprove('teacher002','tc001',past);
await registerAndApprove('cs003','cm001',past);
assert.equal((await range(taughtClass,'TEACHER','teacher-003',[lesson.id])).createdCount,1);
const beforeCs = await send('cs001','PATCH','/classes/' + taughtClass + '/status',
  {status:'RUNNING'},409);
assert.match(JSON.stringify(beforeCs),/CS support|lack|assign/i);
assert.equal((await range(taughtClass,'CS','cs-003',[lesson.id])).createdCount,1);
await send('cs001','PATCH','/classes/' + taughtClass + '/status',{status:'RUNNING'});

const attendanceUrl = '/classes/' + taughtClass + '/teacher-attendance';
const [original] = await send('cs003','GET',attendanceUrl);
assert.equal(original.assignmentStatus,'ASSIGNED');
const beforeAttendance = await send('cs003','PATCH',
  '/classes/' + taughtClass + '/status',{status:'COMPLETED'},409);
assert.match(JSON.stringify(beforeAttendance),/attendance|điểm danh/i);
await send('cs003','PUT',attendanceUrl + '/' + original.id,{status:'ABSENT'});
const replaced = await range(taughtClass,'TEACHER','teacher-002',
  [lesson.id],'REPLACE');
assert.equal(replaced.createdCount,1);
const history = await send('cs003','GET',attendanceUrl);
const archived = history.find(x => x.id===original.id);
const substitute = history.find(x => x.id!==original.id);
assert.equal(archived.assignmentStatus,'CANCELLED');
assert.equal(archived.attendanceStatus,'ABSENT');
assert.equal(substitute.assignmentStatus,'ASSIGNED');
const readOnly = await send('cs003','PUT',attendanceUrl + '/' + original.id,
  {status:'PRESENT'},404);
assert.ok(readOnly);
await send('cs003','PUT',attendanceUrl + '/' + substitute.id,{status:'PRESENT'});
const completed = await send('cs003','PATCH',
  '/classes/' + taughtClass + '/status',{status:'COMPLETED'});
assert.equal(completed.status,'COMPLETED');
console.log('PASS: RUNNING needs CS, COMPLETED needs teacher attendance, substitution preserves ABSENT history');
console.log('PASS: full PostgreSQL class integrity integration checks');
