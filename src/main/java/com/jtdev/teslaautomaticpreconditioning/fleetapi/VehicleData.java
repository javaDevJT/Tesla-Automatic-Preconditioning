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

    private Long id;
    private Integer user_id;
    private Long vehicle_id;
    private String vin;
    private Object color;
    private String access_type;
    private GranularAccess granular_access;
    private Object tokens;
    private String state;
    private Boolean in_service;
    private String id_s;
    private Boolean calendar_enabled;
    private Integer api_version;
    private Object backseat_token;
    private Object backseat_token_updated_at;
    private Boolean ble_autopair_enrolled;
    private String supercharger_payment_needed;
    private Boolean supercharging_enabled;
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
        private Integer id;
        private String name;
        private Integer days_of_week;
        private Boolean start_enabled;
        private Integer start_time;
        private Boolean end_enabled;
        private Integer end_time;
        private Boolean one_time;
        private Boolean enabled;
        private Double latitude;
        private Double longitude;  // Fixed: lowercase 'l'
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
        private Integer charge_buffer;
        private Integer max_num_charge_schedules;
        private Boolean next_schedule;
        private Boolean show_schedule_complete_state;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChargeScheduleWindow {
        private Integer id;
        private String name;
        private Integer days_of_week;
        private Boolean start_enabled;
        private Integer start_time;
        private Boolean end_enabled;
        private Integer end_time;
        private Boolean one_time;
        private Boolean enabled;
        private Double latitude;
        private Double longitude;  // Fixed: lowercase 'l'
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChargeState {
        private Boolean battery_heater_on;
        private Integer battery_level;
        private Double battery_range;
        private Integer charge_amps;
        private Integer charge_current_request;
        private Integer charge_current_request_max;
        private Boolean charge_enable_request;
        private Integer charge_energy_added;
        private Integer charge_limit_soc;
        private Integer charge_limit_soc_max;
        private Integer charge_limit_soc_min;
        private Integer charge_limit_soc_std;
        private Integer charge_miles_added_ideal;
        private Integer charge_miles_added_rated;
        private Boolean charge_port_cold_weather_mode;
        private String charge_port_color;
        private Boolean charge_port_door_open;
        private String charge_port_latch;
        private Double charge_rate;
        private Integer charger_actual_current;
        private Integer charger_phases;
        private Integer charger_pilot_current;
        private Integer charger_power;
        private Integer charger_voltage;
        private String charging_state;
        private String conn_charge_cable;
        private Double est_battery_range;
        private String fast_charger_brand;
        private Boolean fast_charger_present;
        private String fast_charger_type;
        private Double ideal_battery_range;
        private Integer max_range_charge_counter;
        private Integer minutes_to_full_charge;
        private Object not_enough_power_to_heat;
        private Boolean off_peak_charging_enabled;
        private String off_peak_charging_times;
        private Boolean preconditioning_enabled;
        private String preconditioning_times;
        private String scheduled_charging_mode;
        private Boolean scheduled_charging_pending;
        private Integer scheduled_charging_start_time;
        private Integer scheduled_charging_start_time_app;
        private Integer scheduled_charging_start_time_minutes;
        private Integer scheduled_departure_time;
        private Integer scheduled_departure_time_minutes;
        private Boolean supercharger_session_trip_planner;
        private Integer time_to_full_charge;
        private Long timestamp;
        private Boolean trip_charging;
        private Integer usable_battery_level;
        private Boolean user_charge_enable_request;
        private Double pack_current;
        private Double pack_voltage;
        private Double module_temp_min;
        private Integer module_temp_max;
        private Double energy_remaining;
        private Double lifetime_energy_used;
        private Boolean charging_schedule_override;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClimateState {
        private Boolean allow_cabin_overheat_protection;
        private Boolean auto_seat_climate_left;
        private Boolean auto_seat_climate_right;
        private Boolean auto_steering_wheel_heat;
        private Boolean battery_heater;
        private Object battery_heater_no_power;
        private Boolean bioweapon_mode;
        private String cabin_overheat_protection;
        private Boolean cabin_overheat_protection_actively_cooling;
        private String climate_keeper_mode;
        private String cop_activation_temperature;
        private Integer defrost_mode;
        private Double driver_temp_setting;
        private Integer fan_status;
        private Object hvac_auto_request;
        private Double inside_temp;
        @JsonProperty("is_auto_conditioning_on") private Boolean auto_conditioning_on;
        @JsonProperty("is_climate_on") private Boolean climate_on;
        @JsonProperty("is_front_defroster_on") private Boolean front_defroster_on;
        @JsonProperty("is_preconditioning") private Boolean preconditioning;
        @JsonProperty("is_rear_defroster_on") private Boolean rear_defroster_on;
        private Integer left_temp_direction;
        private Integer max_avail_temp;
        private Integer min_avail_temp;
        private Double outside_temp;
        private Double passenger_temp_setting;
        private Boolean remote_heater_control_enabled;
        private Integer right_temp_direction;
        private Integer seat_fan_front_left;
        private Integer seat_fan_front_right;
        private Integer seat_heater_left;
        private Integer seat_heater_rear_left;
        private Integer seat_heater_rear_right;
        private Integer seat_heater_right;
        private Boolean side_mirror_heaters;
        private Integer steering_wheel_heat_level;
        private Boolean steering_wheel_heater;
        private Boolean supports_fan_only_cabin_overheat_protection;
        private Long timestamp;
        private Boolean wiper_blade_heater;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriveState {
        private Long gps_as_of;                        // Fixed: Long to handle large timestamps
        private Integer heading;
        private Double latitude;
        private Double longitude;                      // Fixed: lowercase 'l'
        private Double native_latitude;
        private Integer native_location_supported;
        private Double native_longitude;               // Fixed: lowercase 'l'
        private String native_type;
        private Integer power;
        private String shift_state;
        private Double speed;                          // Fixed: Double to handle null properly
        private Long timestamp;
        private String active_route_destination;
        private Double active_route_energy_at_arrival; // Fixed: Double for energy values
        private Double active_route_latitude;
        private Double active_route_longitude;         // Fixed: lowercase 'l'
        private Double active_route_miles_to_arrival;
        private Double active_route_minutes_to_arrival;
        private Integer active_route_traffic_minutes_delay;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GranularAccess {
        private Boolean hide_private;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GuiSettings {
        private Boolean gui_24_hour_time;
        private String gui_charge_rate_units;
        private String gui_distance_units;
        private String gui_range_display;
        private String gui_temperature_units;
        private String gui_tirepressure_units;
        private Boolean show_range_units;
        private Long timestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaInfo {
        private Double audio_volume;
        private Double audio_volume_increment;
        private Double audio_volume_max;
        private String media_playback_status;
        private String now_playing_album;
        private String now_playing_artist;
        private Integer now_playing_duration;
        private Integer now_playing_elapsed;
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
        private Boolean remote_control_enabled;
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
        private Integer max_num_precondition_schedules;
        private Boolean next_schedule;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PreconditioningScheduleWindow {
        private Integer id;
        private String name;
        private Integer days_of_week;
        private Integer precondition_time;
        private Boolean one_time;
        private Boolean enabled;
        private Double latitude;
        private Double longitude;  // Fixed: lowercase 'l'
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PreconditionSchedule {
        private Long id;
        private String name;
        private Integer days_of_week;
        private Integer precondition_time;
        private Boolean one_time;
        private Boolean enabled;
        private Double latitude;
        private Double longitude;  // Fixed: lowercase 'l'
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SoftwareUpdate {
        private Integer download_perc;
        private Integer expected_duration_sec;
        private Integer install_perc;
        private String status;
        private String version;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpeedLimitMode {
        private Boolean active;
        private Integer current_limit_mph;
        private Integer max_limit_mph;
        private Integer min_limit_mph;
        private Boolean pin_code_set;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Timestamp {
        private Integer seconds;
        private Integer nanos;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VehicleConfig {
        private Integer badge_version;
        private Boolean can_accept_navigation_requests;
        private Boolean can_actuate_trunks;
        private String car_special_type;
        private String car_type;
        private String charge_port_type;
        private Boolean cop_user_set_temp_supported;
        private Boolean dashcam_clip_save_supported;
        private Boolean default_charge_to_max;
        private String driver_assist;
        private Boolean ece_restrictions;
        private String efficiency_package;
        private Boolean eu_vehicle;
        private String exterior_color;
        private String exterior_trim_override;
        private Boolean has_air_suspension;
        private Boolean has_ludicrous_mode;
        private Boolean has_seat_cooling;
        private String interior_trim_type;  // Fixed: typo and casing
        private Integer key_version;
        private Boolean motorized_charge_port;
        private String paint_color_override;  // Fixed: typo
        private Boolean plg;
        private Boolean pws;
        private String rear_drive_unit;
        private Integer rear_seat_heaters;
        private Integer rear_seat_type;
        private Boolean rhd;
        private String roof_color;
        private Object seat_type;
        private Boolean sentry_preview_supported;
        private String spoiler_type;
        private Integer steering_wheel_type;
        private Object sun_roof_installed;
        private Boolean supports_qr_pairing;
        private String third_row_seats;
        private Long timestamp;
        private String trim_badging;
        private Boolean use_range_badging;
        private Integer utc_offset;
        private Boolean webcam_selfie_supported;
        private Boolean webcam_supported;
        private String wheel_type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VehicleState {
        private Integer api_version;
        private String autopark_state_v3;
        private Boolean calendar_supported;
        private String car_version;
        private Integer center_display_state;
        private Boolean dashcam_clip_save_available;
        private String dashcam_state;
        private Integer df;
        private Integer dr;
        private Integer fd_window;
        private String feature_bitmask;
        private Integer fp_window;
        private Integer ft;
        @JsonProperty("is_user_present") private Boolean user_present;
        private Boolean locked;
        private MediaInfo media_info;
        private MediaState media_state;
        private Boolean notifications_supported;
        private Double odometer;
        private Boolean parsed_calendar_supported;
        private Integer pf;
        private Integer pr;
        private Integer rd_window;
        private Boolean remote_start;
        private Boolean remote_start_enabled;
        private Boolean remote_start_supported;
        private Integer rp_window;
        private Integer rt;
        private Integer santa_mode;
        private Boolean sentry_mode;
        private Boolean sentry_mode_available;
        private Boolean service_mode;
        private Boolean service_mode_plus;
        private SoftwareUpdate software_update;
        private SpeedLimitMode speed_limit_mode;
        private Long timestamp;
        private Boolean tpms_hard_warning_fl;
        private Boolean tpms_hard_warning_fr;
        private Boolean tpms_hard_warning_rl;
        private Boolean tpms_hard_warning_rr;
        private Integer tpms_last_seen_pressure_time_fl;
        private Integer tpms_last_seen_pressure_time_fr;
        private Integer tpms_last_seen_pressure_time_rl;
        private Integer tpms_last_seen_pressure_time_rr;
        private Double tpms_pressure_fl;
        private Double tpms_pressure_fr;
        private Double tpms_pressure_rl;
        private Double tpms_pressure_rr;
        private Double tpms_rcp_front_value;
        private Double tpms_rcp_rear_value;
        private Boolean tpms_soft_warning_fl;
        private Boolean tpms_soft_warning_fr;
        private Boolean tpms_soft_warning_rl;
        private Boolean tpms_soft_warning_rr;
        private Boolean valet_mode;
        private Boolean valet_pin_needed;
        private String vehicle_name;
        private Boolean webcam_available;
        private Integer tonneau_state;
        private Integer tonneau_percent_open;
        private Boolean tonneau_in_motion;
        private Integer homelink_device_count;
        private Boolean homelink_nearby;
        private Boolean guest_mode;
        private Boolean pin_to_drive_enabled;
    }


}
