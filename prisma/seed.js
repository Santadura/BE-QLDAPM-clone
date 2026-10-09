const { PrismaClient } = require("@prisma/client");
const bcrypt = require("bcryptjs");

const prisma = new PrismaClient();

async function main() {
  const passwordHash = await bcrypt.hash("123456", 10);

  const roles = [
    ["ADMIN", "Admin"],
    ["TC", "Teaching Coordinator"],
    ["CM", "Center Management"],
    ["TEACHER", "Teacher"],
    ["CS", "Customer Service"],
    ["SALE", "Sale"],
    ["USER", "Default User"],
  ];

  for (const [roleId, roleName] of roles) {
    await prisma.role.upsert({
      where: { roleId },
      update: { roleName },
      create: { roleId, roleName },
    });
  }

  const userSeeds = [
    ["admin", "admin@iig.local", "ADMIN", null, null],
    ["tc001", "tc001@iig.local", "TC", "emp-tc-001", "TC-001"],
    ["cm001", "cm001@iig.local", "CM", "emp-cm-001", "CM-001"],
    ["teacher001", "teacher001@iig.local", "TEACHER", "teacher-001", "GV-001"],
    ["teacher002", "teacher002@iig.local", "TEACHER", "teacher-002", "GV-002"],
    ["teacher003", "teacher003@iig.local", "TEACHER", "teacher-003", "GV-003"],
    ["cs001", "cs001@iig.local", "CS", "cs-001", "CS-001"],
    ["cs002", "cs002@iig.local", "CS", "cs-002", "CS-002"],
    ["cs003", "cs003@iig.local", "CS", "cs-003", "CS-003"],
  ];

  for (const [username, email, roleId, employeeId, employeeCode] of userSeeds) {
    await prisma.user.upsert({
      where: { username },
      update: { email, roleId, status: "ACTIVE" },
      create: {
        username,
        email,
        passwordHash,
        roleId,
        status: "ACTIVE",
      },
    });

    if (employeeId) {
      const names = {
        teacher001: "David Miller",
        teacher002: "Helena Costa",
        teacher003: "Robert Taylor",
        cs001: "Current CS",
        cs002: "Nguyen Minh Chau",
        cs003: "Tran Quoc Huy",
        tc001: "Teaching Coordinator",
        cm001: "Center Manager",
      };

      await prisma.employee.upsert({
        where: { employeeId },
        update: {
          username,
          employeeCode,
          fullName: names[username] ?? username,
          status: "ACTIVE",
        },
        create: {
          employeeId,
          username,
          employeeCode,
          fullName: names[username] ?? username,
          status: "ACTIVE",
        },
      });
    }
  }

  const courseSeeds = [
    ["course-ielts", "IELTS"],
    ["course-toeic", "TOEIC"],
    ["course-sat", "SAT"],
    ["course-toefl", "TOEFL iBT"],
  ];

  for (const [courseId, name] of courseSeeds) {
    await prisma.course.upsert({
      where: { courseId },
      update: { name, status: "ACTIVE" },
      create: { courseId, name, status: "ACTIVE" },
    });
  }

  const classSeeds = [
    ["class-0001", "course-ielts", "IELTS-M75-04", "IELTS Mastery 7.5", "2026-10-06", "2027-01-30", "RUNNING", "admin"],
    ["class-0002", "course-toeic", "TOEIC-850-02", "TOEIC Intensive 850+", "2026-10-12", "2027-01-15", "READY", "admin"],
    ["class-0003", "course-toefl", "TOEFL-IBT-01", "TOEFL iBT Complete", "2026-09-15", "2026-12-20", "RUNNING", "cs001"],
    ["class-0004", "course-sat", "SAT-ADV-03", "SAT Math Advanced", "2026-11-01", "2027-02-28", "DRAFT", "admin"],
    ["class-0005", "course-ielts", "IELTS-F65-08", "IELTS Foundation 6.5", "2026-08-01", "2026-11-30", "COMPLETED", "cs001"],
    ["class-0006", "course-ielts", "IELTS-WR-02", "IELTS Writing Intensive", "2026-10-20", "2026-12-22", "READY", "admin"],
    ["class-0007", "course-toeic", "TOEIC-650-01", "TOEIC Foundation 650", "2026-07-01", "2026-09-30", "CLOSED", "admin"],
    ["class-0008", "course-sat", "SAT-FOUND-01", "SAT Foundation", "2026-10-25", "2027-02-10", "DRAFT", "cs001"],
  ];

  for (const [classId, courseId, classCode, name, startDate, endDate, status, createdByUsername] of classSeeds) {
    await prisma.class.upsert({
      where: { classId },
      update: {
        courseId,
        classCode,
        name,
        startDate: new Date(startDate),
        endDate: new Date(endDate),
        status,
        createdByUsername,
      },
      create: {
        classId,
        courseId,
        classCode,
        name,
        startDate: new Date(startDate),
        endDate: new Date(endDate),
        status,
        createdByUsername,
      },
    });
  }

  const classTargetSeeds = [
    ["class-target-001", "class-0001", "OVERALL_BAND", 7.5],
    ["class-target-002", "class-0002", "LR_TOTAL", 850],
    ["class-target-003", "class-0002", "SW_TOTAL", 300],
    ["class-target-004", "class-0003", "OVERALL_1_6", 4.5],
    ["class-target-005", "class-0004", "TOTAL", 1300],
    ["class-target-006", "class-0005", "OVERALL_BAND", 6.5],
    ["class-target-007", "class-0006", "OVERALL_BAND", 7.0],
    ["class-target-008", "class-0007", "LR_TOTAL", 650],
    ["class-target-009", "class-0007", "SW_TOTAL", 200],
    ["class-target-010", "class-0008", "TOTAL", 1100],
  ];

  for (const [classTargetRequirementId, classId, targetType, requiredTarget] of classTargetSeeds) {
    await prisma.classTargetRequirement.upsert({
      where: { classTargetRequirementId },
      update: { classId, targetType, requiredTarget },
      create: { classTargetRequirementId, classId, targetType, requiredTarget },
    });
  }

  const students = [
    ["student-0001", "STU-2024-0891", "Minh Anh Nguyen", "+84 912 345 678", "minhanh.nguyen@email.com", "Active"],
    ["student-0002", "STU-2024-0892", "Duc Thang Tran", "+84 913 276 428", "thang.tran@email.com", "Active"],
    ["student-0003", "STU-2024-0895", "Phuong Linh Vo", "+84 983 112 456", "linh.vo@email.com", "Active"],
    ["student-0004", "STU-2024-0870", "Hoang Nam Le", "+84 912 466 113", "nam.le@email.com", "On Leave"],
    ["student-0005", "STU-2024-0864", "Mai Huong Dang", "+84 934 778 202", "huong.dang@email.com", "Active"],
    ["student-0006", "STU-2024-0752", "Quoc Bao Pham", "+84 906 325 445", "bao.pham@email.com", "Graduated"],
    ["student-0007", "STU-2024-0899", "Thu Ha Nguyen", "+84 928 349 118", "ha.nguyen@email.com", "Active"],
  ];

  for (const [studentId, studentCode, fullName, phone, email, status] of students) {
    await prisma.student.upsert({
      where: { studentId },
      update: { studentCode, fullName, phone, email, status },
      create: { studentId, studentCode, fullName, phone, email, status },
    });
  }

  const studentTargetSeeds = [
    ["st-001-ielts", "student-0001", "course-ielts", "OVERALL_BAND", 7.5],
    ["st-001-toeic-lr", "student-0001", "course-toeic", "LR_TOTAL", 850],
    ["st-001-toeic-sw", "student-0001", "course-toeic", "SW_TOTAL", 320],
    ["st-002-toeic-lr", "student-0002", "course-toeic", "LR_TOTAL", 850],
    ["st-002-toeic-sw", "student-0002", "course-toeic", "SW_TOTAL", 300],
    ["st-002-ielts", "student-0002", "course-ielts", "OVERALL_BAND", 7.5],
    ["st-003-toefl", "student-0003", "course-toefl", "OVERALL_1_6", 4.5],
    ["st-005-ielts", "student-0005", "course-ielts", "OVERALL_BAND", 7.5],
    ["st-007-ielts", "student-0007", "course-ielts", "OVERALL_BAND", 8.0],
  ];

  for (const [studentTargetId, studentId, courseId, targetType, targetValue] of studentTargetSeeds) {
    await prisma.studentTarget.upsert({
      where: { studentTargetId },
      update: { studentId, courseId, targetType, targetValue },
      create: { studentTargetId, studentId, courseId, targetType, targetValue },
    });
  }

  for (const [index, studentId] of ["student-0001","student-0002","student-0005"].entries()) {
    const classStudentId = `class-student-${String(index + 1).padStart(3, "0")}`;
    await prisma.classStudent.upsert({
      where: { classStudentId },
      update: { classId: "class-0001", studentId, status: "ACTIVE", inactiveReason: null },
      create: { classStudentId, classId: "class-0001", studentId, status: "ACTIVE" },
    });
  }

  await prisma.classAccessScope.upsert({
    where: { classAccessScopeId: "class-scope-001" },
    update: { employeeId: "cs-001", classId: "class-0001", status: "ACTIVE", source: "ASSIGNED_SCOPE" },
    create: { classAccessScopeId: "class-scope-001", employeeId: "cs-001", classId: "class-0001", status: "ACTIVE", source: "ASSIGNED_SCOPE" },
  });

  await prisma.teachingSchedule.upsert({
    where: { teachingScheduleId: "teaching-001" },
    update: {},
    create: {
      teachingScheduleId: "teaching-001",
      teacherId: "teacher-001",
      classId: "class-0001",
      date: new Date("2026-10-06"),
      startTime: new Date("1970-01-01T18:30:00Z"),
      endTime: new Date("1970-01-01T20:30:00Z"),
      status: "ASSIGNED",
      assignedById: "emp-tc-001",
    },
  });

  console.log("Seed completed.");
  console.log("Demo login: admin / 123456");
}

main()
  .catch((error) => {
    console.error(error);
    process.exit(1);
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
