package com.jtdev.teslaautomaticpreconditioning.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks API usage per vehicle to enforce rate limits and prevent excessive API calls
 */
@Slf4j
@Component
public class VehicleApiUsageTracker {
    
    private static final int MAX_COMMANDS_PER_HOUR = 500;
    private static final long ONE_HOUR_MILLIS = 60 * 60 * 1000L;
    
    // Track API commands per VIN with timestamps
    private final Map<String, List<ApiCommand>> vinCommandHistory = new ConcurrentHashMap<>();
    
    public enum CommandType {
        WAKE_VEHICLE(3),     // Wake commands are expensive
        ADD_SCHEDULE(2),     // Adding schedules
        DELETE_SCHEDULE(2),  // Deleting schedules
        MODIFY_SCHEDULE(2),  // Modifying schedules
        GET_DATA(1),         // Getting vehicle data
        START_DEFROST(2);    // Starting defrost
        
        private final int weight;
        
        CommandType(int weight) {
            this.weight = weight;
        }
        
        public int getWeight() {
            return weight;
        }
    }
    
    private static class ApiCommand {
        final CommandType type;
        final long timestamp;
        final int weight;
        
        ApiCommand(CommandType type, long timestamp) {
            this.type = type;
            this.timestamp = timestamp;
            this.weight = type.getWeight();
        }
    }
    
    /**
     * Check if we can execute a command for the given VIN without exceeding rate limits
     */
    public boolean canExecuteCommand(String vin, CommandType commandType) {
        cleanupOldCommands(vin);
        
        List<ApiCommand> commands = vinCommandHistory.computeIfAbsent(vin, k -> new ArrayList<>());
        int currentWeight = commands.stream().mapToInt(c -> c.weight).sum();
        
        boolean canExecute = (currentWeight + commandType.getWeight()) <= MAX_COMMANDS_PER_HOUR;
        
        if (!canExecute) {
            log.warn("Rate limit reached for VIN {}: current weight {} + new command weight {} > limit {}", 
                    vin, currentWeight, commandType.getWeight(), MAX_COMMANDS_PER_HOUR);
        }
        
        return canExecute;
    }
    
    /**
     * Record that a command was executed for the given VIN
     */
    public void recordCommand(String vin, CommandType commandType) {
        List<ApiCommand> commands = vinCommandHistory.computeIfAbsent(vin, k -> new ArrayList<>());
        commands.add(new ApiCommand(commandType, System.currentTimeMillis()));
        
        log.debug("Recorded {} command for VIN {}, current usage: {}/{}", 
                commandType, vin, getCurrentUsageWeight(vin), MAX_COMMANDS_PER_HOUR);
    }
    
    /**
     * Get current API usage weight for a VIN in the last hour
     */
    public int getCurrentUsageWeight(String vin) {
        cleanupOldCommands(vin);
        List<ApiCommand> commands = vinCommandHistory.get(vin);
        return commands == null ? 0 : commands.stream().mapToInt(c -> c.weight).sum();
    }
    
    /**
     * Get remaining API capacity for a VIN
     */
    public int getRemainingCapacity(String vin) {
        return MAX_COMMANDS_PER_HOUR - getCurrentUsageWeight(vin);
    }
    
    /**
     * Check if a command should be deferred due to rate limiting
     */
    public boolean shouldDeferCommand(String vin, CommandType commandType) {
        return !canExecuteCommand(vin, commandType);
    }
    
    /**
     * Get time until rate limit resets (in milliseconds)
     */
    public long getTimeUntilReset(String vin) {
        List<ApiCommand> commands = vinCommandHistory.get(vin);
        if (commands == null || commands.isEmpty()) {
            return 0;
        }
        
        long oldestCommandTime = commands.stream()
                .mapToLong(c -> c.timestamp)
                .min()
                .orElse(System.currentTimeMillis());
        
        long resetTime = oldestCommandTime + ONE_HOUR_MILLIS;
        return Math.max(0, resetTime - System.currentTimeMillis());
    }
    
    /**
     * Remove commands older than 1 hour
     */
    private void cleanupOldCommands(String vin) {
        List<ApiCommand> commands = vinCommandHistory.get(vin);
        if (commands != null) {
            long cutoffTime = System.currentTimeMillis() - ONE_HOUR_MILLIS;
            commands.removeIf(cmd -> cmd.timestamp < cutoffTime);
            
            if (commands.isEmpty()) {
                vinCommandHistory.remove(vin);
            }
        }
    }
    
    /**
     * Get usage statistics for all vehicles
     */
    public Map<String, Integer> getAllVehicleUsage() {
        Map<String, Integer> usage = new ConcurrentHashMap<>();
        vinCommandHistory.forEach((vin, commands) -> {
            cleanupOldCommands(vin);
            usage.put(vin, getCurrentUsageWeight(vin));
        });
        return usage;
    }
    
    /**
     * Clear all usage history (for testing or reset purposes)
     */
    public void clearHistory() {
        vinCommandHistory.clear();
        log.info("Cleared all API usage history");
    }
}
