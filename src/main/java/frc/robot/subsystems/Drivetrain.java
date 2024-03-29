package frc.robot.subsystems;

import com.ctre.phoenix.sensors.WPI_PigeonIMU;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMaxLowLevel;

import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.RamseteController;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.filter.SlewRateLimiter;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.DifferentialDriveKinematics;
import edu.wpi.first.math.kinematics.DifferentialDriveOdometry;
import edu.wpi.first.math.kinematics.DifferentialDriveWheelSpeeds;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.trajectory.Trajectory;
import edu.wpi.first.math.trajectory.TrajectoryConfig;
import edu.wpi.first.math.trajectory.TrajectoryGenerator;
import edu.wpi.first.math.trajectory.constraint.DifferentialDriveVoltageConstraint;
import edu.wpi.first.wpilibj.motorcontrol.MotorControllerGroup;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.CommandBase;
import edu.wpi.first.wpilibj2.command.RamseteCommand;
import edu.wpi.first.wpilibj2.command.SequentialCommandGroup;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class Drivetrain extends SubsystemBase {
  private static Drivetrain instance;

  private final WPI_PigeonIMU gyro = new WPI_PigeonIMU(10);

  private final DifferentialDriveKinematics kinematics = new DifferentialDriveKinematics(
      Constants.Drivetrain.TRACK_WIDTH_METERS);

  private final CANSparkMax frontLeft = new CANSparkMax(15, CANSparkMaxLowLevel.MotorType.kBrushless);
  private final CANSparkMax frontRight = new CANSparkMax(14, CANSparkMaxLowLevel.MotorType.kBrushless);
  private final CANSparkMax backLeft = new CANSparkMax(12, CANSparkMaxLowLevel.MotorType.kBrushless);
  private final CANSparkMax backRight = new CANSparkMax(13, CANSparkMaxLowLevel.MotorType.kBrushless);

  private final MotorControllerGroup left = new MotorControllerGroup(backLeft, frontLeft);
  private final MotorControllerGroup right = new MotorControllerGroup(backRight, frontRight);

  private SimpleMotorFeedforward left_feedforward;
  private SimpleMotorFeedforward right_feedforward;
  private SimpleMotorFeedforward avg_feedforward;

  private DifferentialDriveOdometry odometry;

  private Arm arm = Arm.getInstance();

  private Drivetrain() {
    this.left_feedforward = new SimpleMotorFeedforward(Constants.Drivetrain.Feedforward.Left.S,
        Constants.Drivetrain.Feedforward.Left.V, Constants.Drivetrain.Feedforward.Left.A);
    this.right_feedforward = new SimpleMotorFeedforward(Constants.Drivetrain.Feedforward.Right.S,
        Constants.Drivetrain.Feedforward.Right.V, Constants.Drivetrain.Feedforward.Right.A);
    this.avg_feedforward = new SimpleMotorFeedforward(Constants.Drivetrain.Feedforward.Avg.S,
        Constants.Drivetrain.Feedforward.Avg.V, Constants.Drivetrain.Feedforward.Avg.A);

    frontLeft.setIdleMode(CANSparkMax.IdleMode.kBrake);
    backLeft.setIdleMode(CANSparkMax.IdleMode.kBrake);
    frontRight.setIdleMode(CANSparkMax.IdleMode.kBrake);
    backRight.setIdleMode(CANSparkMax.IdleMode.kBrake);

    frontLeft.setInverted(false);
    backLeft.setInverted(false);
    frontRight.setInverted(true);
    backRight.setInverted(true);

    frontLeft.getEncoder().setPositionConversionFactor(Constants.Drivetrain.POSITION_CONVERSION);
    frontLeft.getEncoder().setVelocityConversionFactor(Constants.Drivetrain.VELOCITY_CONVERSION);
    frontRight.getEncoder().setPositionConversionFactor(Constants.Drivetrain.POSITION_CONVERSION);
    frontRight.getEncoder().setVelocityConversionFactor(Constants.Drivetrain.VELOCITY_CONVERSION);

    if (Constants.Drivetrain.ENABLE_CURRENT_LIMIT) {
      frontLeft.setSmartCurrentLimit(Constants.Drivetrain.CURRENT_LIMIT);
      backLeft.setSmartCurrentLimit(Constants.Drivetrain.CURRENT_LIMIT);
      frontRight.setSmartCurrentLimit(Constants.Drivetrain.CURRENT_LIMIT);
      backRight.setSmartCurrentLimit(Constants.Drivetrain.CURRENT_LIMIT);
    }

    this.gyro.setYaw(0.0);
    this.frontLeft.getEncoder().setPosition(0.0);
    this.frontRight.getEncoder().setPosition(0.0);

    this.odometry = new DifferentialDriveOdometry(this.getYawRotation2D(), this.frontLeft.getEncoder().getPosition(),
        this.frontRight.getEncoder().getPosition(), new Pose2d(0.0, 0.0, new Rotation2d(0.0)));
  }

  public static Drivetrain getInstance() {
    return frc.robot.subsystems.Drivetrain.instance == null
        ? frc.robot.subsystems.Drivetrain.instance = new Drivetrain()
        : frc.robot.subsystems.Drivetrain.instance;
  }

  // private final double kDenominator = Math.sin(Math.PI / 2.0 *
  // Constants.Drivetrain.WHEEL_NONLINEARITY);

  // 6.0, 0.0, 0.3 (0.49757)
  // tile: p 0.49757, s 0.21881, v 0.19964, a 0.011839
  PIDController teleopTurnController = new PIDController(0.91628, 0.0, 0.0, Constants.Units.SECONDS_PER_LOOP);
  SimpleMotorFeedforward teleopTurnFeedforward = new SimpleMotorFeedforward(0.45277, 0.21407, 0.020394);

  private SlewRateLimiter forwardSlewLimiter = new SlewRateLimiter(2.0);
  private double forwardSlewLimiterValue = 1;
  private SlewRateLimiter turnSlewLimiter = new SlewRateLimiter(1.0);
  private double wheelGain = 1;

  public void curvatureDrive(double throttle, double wheel, boolean quickTurn) {
    throttle = MathUtil.applyDeadband(throttle, Constants.Drivetrain.DEADBAND);
    wheel = MathUtil.applyDeadband(wheel, Constants.Drivetrain.DEADBAND);

    // if (!quickTurn) {
    // wheel = Math.sin(Math.PI / 2.0 * Constants.Drivetrain.WHEEL_NONLINEARITY *
    // wheel);
    // wheel = Math.sin(Math.PI / 2.0 * Constants.Drivetrain.WHEEL_NONLINEARITY *
    // wheel);
    // wheel = wheel / (kDenominator * kDenominator) * Math.abs(throttle);
    // }

    wheel = wheel * wheel * ((wheel < 0) ? -1 : 1);

    // double vx = throttle * Constants.Drivetrain.DRIVE_MAX_MPS *
    // Constants.Drivetrain.LIMITER;

    double vx = throttle * Constants.Drivetrain.DRIVE_MAX_MPS
        * this.forwardSlewLimiter.calculate(this.forwardSlewLimiterValue);

    double omega;

    if (quickTurn) {
      if (this.arm.isUp()) { // Kinda Slow?
        this.wheelGain = Constants.Drivetrain.ARM_EXTENDED_QUICKTURN_WHEEL_GAIN;
        // System.out.println("quick up");
      } else { // Hecking Fast
        this.wheelGain = Constants.Drivetrain.ARM_STOW_QUICKTURN_WHEEL_GAIN;
        // System.out.println("quick stow");
      }
      // omega = wheel * this.turnSlewLimiter.calculate(this.wheelGain);
      omega = wheel * this.wheelGain;
    } else {
      if (this.arm.isUp()) { // Very Slow
        this.wheelGain = Constants.Drivetrain.ARM_EXTENDED_WHEEL_GAIN;
        // System.out.println("curve up");
      } else { // Interpolate
        // y = ((f - s) / (max)) * x + s
        // y = ((Constants.FAST_WHEEL_TURN_GAIN - Constants.SLOW_WHEEL_TURN_GAIN) /
        // Constants.DRIVE_MAX_MPS) * x + Constants.SLOW_WHEEL_TURN_GAIN
        // y = gain, x = current speed
        // System.out.println("curve stow");
        this.wheelGain = Constants.Drivetrain.SLOW_WHEEL_TURN_GAIN
            - ((Constants.Drivetrain.SLOW_WHEEL_TURN_GAIN - Constants.Drivetrain.FAST_WHEEL_TURN_GAIN))
                * (vx / Constants.Drivetrain.DRIVE_MAX_MPS);
      }
      // omega = wheel * Math.abs(throttle) * this.turnSlewLimiter.calculate(this.wheelGain);
      omega = wheel * Math.abs(throttle) * this.wheelGain;
    }

    SmartDashboard.putNumber("omega", omega);
    SmartDashboard.putNumber("yaw rate", this.getYawRate());
    SmartDashboard.putNumber("vx", vx);

    double turnVolts = (teleopTurnController.calculate(this.getYawRate(), omega)
        + teleopTurnFeedforward.calculate(omega));
    double leftForwardVolts = this.left_feedforward.calculate(vx);
    double rightForwardVolts = this.right_feedforward.calculate(vx);

    this.setDriveVolts(leftForwardVolts - turnVolts, rightForwardVolts + turnVolts);

    // this.setOpenLoopWheelSpeed(this.kinematics.toWheelSpeeds(new
    // ChassisSpeeds(vx, 0.0, omega)));_xyz_dps
  }

  public void updateSlew() {
    this.forwardSlewLimiterValue = (this.arm.isUp()) ? Constants.Drivetrain.ARM_EXTENDED_FORWARD_MULTIPLIER
        : Constants.Drivetrain.ARM_STOW_FORWARD_MULTIPLIER;
    // this.turnSlewLimiterValue = (this.arm.isUp()) ?
    // Constants.Drivetrain.ARM_EXTENDED_TURN_MULTIPLIER :
    // Constants.Drivetrain.ARM_STOW_TURN_MULTIPLIER;
  }

  // public void updateTurnSlew(double speed) {
  // this.wheelGain =
  // }

  public void resetEncoders() {
    this.frontLeft.getEncoder().setPosition(0.0);
    this.frontRight.getEncoder().setPosition(0.0);
  }

  @Override
  public void periodic() {
    SmartDashboard.putNumber("drive position", this.getDriveDistance());
    SmartDashboard.putNumber("drive pitch", this.getPitch());
    SmartDashboard.putNumber("drive yaw", this.getYaw());
    SmartDashboard.putString("drive yaw rotation 2D", this.getYawRotation2D().toString());
    SmartDashboard.putNumber("left drive volts", this.frontLeft.getAppliedOutput() * 12.0);
    SmartDashboard.putNumber("right drive volts", this.frontRight.getAppliedOutput() * 12.0);

    this.odometry.update(this.getYawRotation2D(), this.frontLeft.getEncoder().getPosition(),
        this.frontRight.getEncoder().getPosition());

    SmartDashboard.putString("Odometry", this.odometry.getPoseMeters().toString());
  }

  public DifferentialDriveWheelSpeeds getWheelSpeeds() {
    return new DifferentialDriveWheelSpeeds(this.frontLeft.getEncoder().getVelocity(),
        this.frontRight.getEncoder().getVelocity());
  }

  public Pose2d getPos() {
    return this.odometry.getPoseMeters();
  }

  public void setPos(Pose2d pos) {
    this.odometry.resetPosition(this.getYawRotation2D(), this.frontLeft.getEncoder().getPosition(),
        this.frontRight.getEncoder().getPosition(), pos);
  }

  public void setDriveVolts(double leftVolts, double rightVolts) {
    this.left.setVoltage(leftVolts);
    this.right.setVoltage(rightVolts);
  }

  public void setOpenLoopWheelSpeed(@NotNull DifferentialDriveWheelSpeeds speed) {
    this.setDriveVolts(left_feedforward.calculate(speed.leftMetersPerSecond),
        right_feedforward.calculate(speed.rightMetersPerSecond));
  }

  public double getDriveDistance() {
    return (this.frontLeft.getEncoder().getPosition() + this.frontRight.getEncoder().getPosition()) / 2.0;
  }

  public double getPitch() {
    return this.gyro.getRoll();
  }

  public double getYaw() {
    return this.gyro.getYaw() / (180.0 * Math.PI);
  }

  public double getYawRate() {
    return Math.toRadians(-this.gyro.getRate());
  }

  public double getYawDegrees() {
    return this.gyro.getYaw();
  }

  public Rotation2d getYawRotation2D() {
    double ypr[] = { 0, 0, 0 };
    this.gyro.getYawPitchRoll(ypr);
    return Rotation2d.fromDegrees(Math.IEEEremainder(ypr[0], 360.0d));
  }

  public CommandBase resetEncodersCommand() {
    return runOnce(() -> {
      this.resetEncoders();
    });
  }

  public CommandBase crawlDistance(double meters, double meters_per_second) {
    return run(() -> this.setOpenLoopWheelSpeed(new DifferentialDriveWheelSpeeds(meters_per_second, meters_per_second)))
        .until(() -> Math.abs(this.getDriveDistance() - meters) <= 0.125)
        .andThen(() -> this.setOpenLoopWheelSpeed(new DifferentialDriveWheelSpeeds(0, 0)), this);
  }

  public CommandBase crawlUntilTilt(double meters_per_second, double angle) {
    return run(() -> this.setOpenLoopWheelSpeed(new DifferentialDriveWheelSpeeds(meters_per_second, meters_per_second)))
        .until(() -> Math.abs(this.getPitch()) >= angle);
  }

  public CommandBase crawlUntilTilt(double meters_per_second) {
    return this.crawlUntilTilt(meters_per_second, Constants.Drivetrain.CHARGE_STATION_PITCH);
  }

  public CommandBase crawlUntilLevel(double meters_per_second) {
    return run(() -> this.setOpenLoopWheelSpeed(new DifferentialDriveWheelSpeeds(meters_per_second, meters_per_second)))
        .until(() -> Math.abs(this.getPitch()) <= Constants.Drivetrain.LEVEL_PITCH);
  }

  public CommandBase crawlUntilOverBackwards(double meters_per_second) {
    return run(() -> this.setOpenLoopWheelSpeed(new DifferentialDriveWheelSpeeds(meters_per_second, meters_per_second)))
        .until(() -> this.getPitch() >= 10.0)
        .andThen(() -> this.setOpenLoopWheelSpeed(new DifferentialDriveWheelSpeeds(meters_per_second, meters_per_second)))
        .until(() -> Math.abs(this.getPitch()) <= 2.0)
        .andThen(crawlDistance(-0.25, meters_per_second))
        .andThen(runOnce(() -> this.setOpenLoopWheelSpeed(new DifferentialDriveWheelSpeeds(0, 0))));
  }

  public CommandBase getTrajectoryCommand(List<Pose2d> waypoints, boolean isReversed) {
    // return
    DifferentialDriveVoltageConstraint vc = new DifferentialDriveVoltageConstraint(this.avg_feedforward,
        this.kinematics, 10);
    TrajectoryConfig tc = new TrajectoryConfig(Constants.Auto.Path.maxVelocity, Constants.Auto.Path.maxAcceleration)
        .setKinematics(this.kinematics).addConstraint(vc).setReversed(isReversed);

    Trajectory t = TrajectoryGenerator.generateTrajectory(waypoints, tc);

    RamseteCommand rc = new RamseteCommand(
        t,
        this::getPos,
        new RamseteController(Constants.Auto.Path.RamseteB, Constants.Auto.Path.RamseteZeta),
        this.avg_feedforward,
        this.kinematics,
        this::getWheelSpeeds,
        new PIDController(Constants.Drivetrain.PIDLoop.LeftP, 0.0, 0.0, Constants.Units.SECONDS_PER_LOOP),
        new PIDController(Constants.Drivetrain.PIDLoop.RightP, 0.0, 0.0, Constants.Units.SECONDS_PER_LOOP),
        this::setDriveVolts,
        this);

    return rc.andThen(() -> this.setDriveVolts(0.0, 0.0));
  }

  public DifferentialDriveKinematics getKinematics() {
    return this.kinematics;
  }

  public static Pose2d point(double x, double y, double a) {
    return new Pose2d(x, y, new Rotation2d(a));
  }

  // public CommandBase turnInPlace(double angle) {
  // // Relative angles, not absolute angles.
  // double turn = (angle > 90 ? 1 : angle < -90 ? -1 : Math.sin(angle)) *
  // Constants.Drivetrain.WHEEL_TURN_GAIN * 0.25;
  //
  // double initial_yaw = this.getYaw();
  // return run(() -> this.setOpenLoopWheelSpeed(kinematics.toWheelSpeeds(new
  // ChassisSpeeds(0.0, 0.0, turn))))
  // .until(() -> Math.abs(this.getYawDegrees() - initial_yaw) >=
  // Math.abs(angle));
  // }

  public static void scheduleSequence(Command... commands) {
    new SequentialCommandGroup(commands).schedule();
  }
}
