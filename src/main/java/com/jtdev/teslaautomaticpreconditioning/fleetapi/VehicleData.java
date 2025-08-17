package com.jtdev.teslaautomaticpreconditioning.fleetapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VehicleData {

    private long id;
    private int user_id;
    private long vehicle_id;
    private String vin;
    private Object color;
    private String access_type;
    private GranularAccess granular_access;
    private Object tokens;
    private String state;
    private boolean in_service;
    private String id_s;
    private boolean calendar_enabled;
    private int api_version;
    private Object backseat_token;
    private Object backseat_token_updated_at;
    private boolean ble_autopair_enrolled;
    private String supercharger_payment_needed;
    private boolean supercharging_enabled;
    private ChargeState charge_state;
    private ClimateState climate_state;
    private DriveState drive_state;
    private GuiSettings gui_settings;
    private VehicleConfig vehicle_config;
    private VehicleState vehicle_state;
    private ChargeScheduleData charge_schedule_data;
    private PreconditioningScheduleData preconditioning_schedule_data;
    private String display_name;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChargeSchedule {
        private int id;
        private String name;
        private int days_of_week;
        private boolean start_enabled;
        private int start_time;
        private boolean end_enabled;
        private int end_time;
        private boolean one_time;
        private boolean enabled;
        private double latitude;
        private double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChargeScheduleData {
        private ArrayList<ChargeSchedule> charge_schedules;
        private Timestamp timestamp;
        private ChargeScheduleWindow charge_schedule_window;
        private int charge_buffer;
        private int max_num_charge_schedules;
        private boolean next_schedule;
        private boolean show_schedule_complete_state;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChargeScheduleWindow {
        private int id;
        private String name;
        private int days_of_week;
        private boolean start_enabled;
        private int start_time;
        private boolean end_enabled;
        private int end_time;
        private boolean one_time;
        private boolean enabled;
        private double latitude;
        private double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChargeState {
        private boolean battery_heater_on;
        private int battery_level;
        private double battery_range;
        private int charge_amps;
        private int charge_current_request;
        private int charge_current_request_max;
        private boolean charge_enable_request;
        private int charge_energy_added;
        private int charge_limit_soc;
        private int charge_limit_soc_max;
        private int charge_limit_soc_min;
        private int charge_limit_soc_std;
        private int charge_miles_added_ideal;
        private int charge_miles_added_rated;
        private boolean charge_port_cold_weather_mode;
        private String charge_port_color;
        private boolean charge_port_door_open;
        private String charge_port_latch;
        private double charge_rate;
        private int charger_actual_current;
        private int charger_phases;
        private int charger_pilot_current;
        private int charger_power;
        private int charger_voltage;
        private String charging_state;
        private String conn_charge_cable;
        private double est_battery_range;
        private String fast_charger_brand;
        private boolean fast_charger_present;
        private String fast_charger_type;
        private double ideal_battery_range;
        private int max_range_charge_counter;
        private int minutes_to_full_charge;
        private Object not_enough_power_to_heat;
        private boolean off_peak_charging_enabled;
        private String off_peak_charging_times;
        private boolean preconditioning_enabled;
        private String preconditioning_times;
        private String scheduled_charging_mode;
        private boolean scheduled_charging_pending;
        private int scheduled_charging_start_time;
        private int scheduled_charging_start_time_app;
        private int scheduled_charging_start_time_minutes;
        private int scheduled_departure_time;
        private int scheduled_departure_time_minutes;
        private boolean supercharger_session_trip_planner;
        private int time_to_full_charge;
        private long timestamp;
        private boolean trip_charging;
        private int usable_battery_level;
        private boolean user_charge_enable_request;
        private double pack_current;
        private double pack_voltage;
        private double module_temp_min;
        private int module_temp_max;
        private double energy_remaining;
        private double lifetime_energy_used;
        private boolean charging_schedule_override;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClimateState {
        private boolean allow_cabin_overheat_protection;
        private boolean auto_seat_climate_left;
        private boolean auto_seat_climate_right;
        private boolean auto_steering_wheel_heat;
        private boolean battery_heater;
        private Object battery_heater_no_power;
        private boolean bioweapon_mode;
        private String cabin_overheat_protection;
        private boolean cabin_overheat_protection_actively_cooling;
        private String climate_keeper_mode;
        private String cop_activation_temperature;
        private int defrost_mode;
        private double driver_temp_setting;
        private int fan_status;
        private Object hvac_auto_request;
        private double inside_temp;
        @JsonProperty("is_auto_conditioning_on") private boolean auto_conditioning_on;
        @JsonProperty("is_climate_on") private boolean climate_on;
        @JsonProperty("is_front_defroster_on") private boolean front_defroster_on;
        @JsonProperty("is_preconditioning") private boolean preconditioning;
        @JsonProperty("is_rear_defroster_on") private boolean rear_defroster_on;
        private int left_temp_direction;
        private int max_avail_temp;
        private int min_avail_temp;
        private double outside_temp;
        private double passenger_temp_setting;
        private boolean remote_heater_control_enabled;
        private int right_temp_direction;
        private int seat_fan_front_left;
        private int seat_fan_front_right;
        private int seat_heater_left;
        private int seat_heater_rear_left;
        private int seat_heater_rear_right;
        private int seat_heater_right;
        private boolean side_mirror_heaters;
        private int steering_wheel_heat_level;
        private boolean steering_wheel_heater;
        private boolean supports_fan_only_cabin_overheat_protection;
        private long timestamp;
        private boolean wiper_blade_heater;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriveState {
        private int gps_as_of;
        private int heading;
        private double latitude;
        private double longitude;
        private double native_latitude;
        private int native_location_supported;
        private double native_longitude;
        private String native_type;
        private int power;
        private String shift_state;
        private int speed;
        private long timestamp;
        private String active_route_destination;
        private int active_route_energy_at_arrival;
        private double active_route_latitude;
        private double active_route_longitude;
        private double active_route_miles_to_arrival;
        private double active_route_minutes_to_arrival;
        private int active_route_traffic_minutes_delay;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GranularAccess {
        private boolean hide_private;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GuiSettings {
        private boolean gui_24_hour_time;
        private String gui_charge_rate_units;
        private String gui_distance_units;
        private String gui_range_display;
        private String gui_temperature_units;
        private String gui_tirepressure_units;
        private boolean show_range_units;
        private long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaInfo {
        private double audio_volume;
        private double audio_volume_increment;
        private double audio_volume_max;
        private String media_playback_status;
        private String now_playing_album;
        private String now_playing_artist;
        private int now_playing_duration;
        private int now_playing_elapsed;
        private String now_playing_source;
        private String now_playing_station;
        private String now_playing_title;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaState {
        private boolean remote_control_enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PreconditioningScheduleData {
        private ArrayList<PreconditionSchedule> precondition_schedules;
        private Timestamp timestamp;
        private PreconditioningScheduleWindow preconditioning_schedule_window;
        private int max_num_precondition_schedules;
        private boolean next_schedule;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PreconditioningScheduleWindow {
        private int id;
        private String name;
        private int days_of_week;
        private int precondition_time;
        private boolean one_time;
        private boolean enabled;
        private double latitude;
        private double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PreconditionSchedule {
        private int id;
        private String name;
        private int days_of_week;
        private int precondition_time;
        private boolean one_time;
        private boolean enabled;
        private double latitude;
        private double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SoftwareUpdate {
        private int download_perc;
        private int expected_duration_sec;
        private int install_perc;
        private String status;
        private String version;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpeedLimitMode {
        private boolean active;
        private int current_limit_mph;
        private int max_limit_mph;
        private int min_limit_mph;
        private boolean pin_code_set;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Timestamp {
        private int seconds;
        private int nanos;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VehicleConfig {
        private int badge_version;
        private boolean can_accept_navigation_requests;
        private boolean can_actuate_trunks;
        private String car_special_type;
        private String car_type;
        private String charge_port_type;
        private boolean cop_user_set_temp_supported;
        private boolean dashcam_clip_save_supported;
        private boolean default_charge_to_max;
        private String driver_assist;
        private boolean ece_restrictions;
        private String efficiency_package;
        private boolean eu_vehicle;
        private String exterior_color;
        private String exterior_trim_override;
        private boolean has_air_suspension;
        private boolean has_ludicrous_mode;
        private boolean has_seat_cooling;
        private String interior_trim_type;
        private int key_version;
        private boolean motorized_charge_port;
        private String paint_color_override;
        private boolean plg;
        private boolean pws;
        private String rear_drive_unit;
        private int rear_seat_heaters;
        private int rear_seat_type;
        private boolean rhd;
        private String roof_color;
        private Object seat_type;
        private boolean sentry_preview_supported;
        private String spoiler_type;
        private int steering_wheel_type;
        private Object sun_roof_installed;
        private boolean supports_qr_pairing;
        private String third_row_seats;
        private long timestamp;
        private String trim_badging;
        private boolean use_range_badging;
        private int utc_offset;
        private boolean webcam_selfie_supported;
        private boolean webcam_supported;
        private String wheel_type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VehicleState {
        private int api_version;
        private String autopark_state_v3;
        private boolean calendar_supported;
        private String car_version;
        private int center_display_state;
        private boolean dashcam_clip_save_available;
        private String dashcam_state;
        private int df;
        private int dr;
        private int fd_window;
        private String feature_bitmask;
        private int fp_window;
        private int ft;
        @JsonProperty("is_user_present") private boolean user_present;
        private boolean locked;
        private MediaInfo media_info;
        private MediaState media_state;
        private boolean notifications_supported;
        private double odometer;
        private boolean parsed_calendar_supported;
        private int pf;
        private int pr;
        private int rd_window;
        private boolean remote_start;
        private boolean remote_start_enabled;
        private boolean remote_start_supported;
        private int rp_window;
        private int rt;
        private int santa_mode;
        private boolean sentry_mode;
        private boolean sentry_mode_available;
        private boolean service_mode;
        private boolean service_mode_plus;
        private SoftwareUpdate software_update;
        private SpeedLimitMode speed_limit_mode;
        private long timestamp;
        private boolean tpms_hard_warning_fl;
        private boolean tpms_hard_warning_fr;
        private boolean tpms_hard_warning_rl;
        private boolean tpms_hard_warning_rr;
        private int tpms_last_seen_pressure_time_fl;
        private int tpms_last_seen_pressure_time_fr;
        private int tpms_last_seen_pressure_time_rl;
        private int tpms_last_seen_pressure_time_rr;
        private double tpms_pressure_fl;
        private double tpms_pressure_fr;
        private double tpms_pressure_rl;
        private double tpms_pressure_rr;
        private double tpms_rcp_front_value;
        private double tpms_rcp_rear_value;
        private boolean tpms_soft_warning_fl;
        private boolean tpms_soft_warning_fr;
        private boolean tpms_soft_warning_rl;
        private boolean tpms_soft_warning_rr;
        private boolean valet_mode;
        private boolean valet_pin_needed;
        private String vehicle_name;
        private boolean webcam_available;
        private int tonneau_state;
        private int tonneau_percent_open;
        private boolean tonneau_in_motion;
        private int homelink_device_count;
        private boolean homelink_nearby;
        private boolean guest_mode;
        private boolean pin_to_drive_enabled;
    }


}
